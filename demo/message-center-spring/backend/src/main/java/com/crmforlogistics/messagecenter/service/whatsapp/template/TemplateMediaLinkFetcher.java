package com.crmforlogistics.messagecenter.service.whatsapp.template;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;

/**
 * 把一个外链图片抓下来，交给上传链路。
 *
 * <h2>本项目里唯一一处「服务端主动访问用户给的地址」</h2>
 * 其它对外动作都是我们<b>发给</b>一个已知的服务商（CAMS、SMTP）；这一处不同：地址来自用户，
 * 而请求由服务端发出。这就是 SSRF 的经典形状 —— 攻击者让服务器去访问它本来够不着的位置
 * （内网面板、云厂商的元数据服务、本地回环上的管理端口），再把响应读回来。
 * 在这里，响应还会被<b>存进服务商的公开存储并拿到一个公网 URL</b>，
 * 也就是说一次成功的 SSRF 不只是「读到了」，而是「把一个可分享的副本留在了外面」。
 *
 * <p>所以反 SSRF 的所有判断都收在这一个类里，且每一条都写在下面：
 * <ol>
 *   <li><b>只认 https。</b> http 的响应在途中可以被改掉，而这张图会跟着模板提交给平台，
 *       我们这边撤不回。这条是刻意的窄 —— 放宽它只需要改一行，但改之前要先想清楚
 *       「中间人换一张图」的代价由谁承担。</li>
 *   <li><b>host 不许是 IP 字面量。</b> 内网服务的地址几乎总是写成 {@code 10.0.0.7} 这样，
 *       而不是一个有 DNS 记录的域名。直接拒掉所有 IP，比逐个比对网段更难写错。</li>
 *   <li><b>域名必须解析到公网地址，而且解析出来的<b>每一个</b>都要是公网。</b>
 *       「有一个是公网」就放行等于给多记录域名留了一道门。</li>
 *   <li><b>不跟随重定向。</b> 跟随后每一跳都要重新做上面两条，而漏掉一跳就是绕过。
 *       3xx 直接报错，让用户给最终地址 —— 这也比跟随更容易解释。</li>
 *   <li><b>读的时候就有上限。</b> {@code Content-Length} 是对方说了算的，
 *       不能拿来决定要不要读（见 {@code readBounded}）。</li>
 * </ol>
 *
 * <h2>类型不看响应头，看文件头</h2>
 * {@code Content-Type} 是对方填的任意字符串。判 {@code image/png} / {@code image/jpeg}
 * 用 magic bytes（见 {@link #sniff}），与上传链路 {@code mediaLimit} 只认这两种取值同源。
 *
 * <h2>做不到的那一件事</h2>
 * DNS 解析与真正连接之间有一小段窗口，理论上可以在这段时间里把域名指向内网
 * （DNS rebinding）。要堵住它得自己拿解析结果去连、并保留 SNI 与证书校验，
 * 那是另一件事的复杂度。这里的取舍是：拒掉所有 IP 字面量 + 解析后全量校验，
 * 已经覆盖了「内网地址」这一类现实中的用法。
 */
@Component
public class TemplateMediaLinkFetcher {

    private static final Logger log = LoggerFactory.getLogger(TemplateMediaLinkFetcher.class);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    /** 明确要图片：对方若是内容协商的站点，这条能省掉一次跳转。 */
    private static final String ACCEPT = "image/png,image/jpeg";

    /** 一个诚实的自称。伪装成浏览器没有必要 —— 我们确实是一个服务端程序在抓图。 */
    private static final String USER_AGENT = "crmforlogistics-message-center/1.0 (template media ingest)";

    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};
    private static final byte[] JPEG_MAGIC = {(byte) 0xff, (byte) 0xd8, (byte) 0xff};

    /** IPv4 的点分十进制写法。用它挡 IP 字面量，不解析成数值 —— 后者会接受 {@code 0177.0.0.1} 这类写法。 */
    private static final String IPV4_LITERAL = "\\d{1,3}(\\.\\d{1,3}){3}";

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            // 见类注释第 4 条：跟随重定向就等于把上面那些检查交给对方决定要不要再用一次。
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    /** 一次抓取的结果：字节、由文件头判出的类型、以及给服务商用的文件名。 */
    public record Fetched(byte[] bytes, String contentType, String fileName) {
    }

    public Fetched fetch(String rawUrl) {
        URI uri = requireHttpsUri(rawUrl);
        requirePublicHost(uri.getHost());
        HttpResponse<InputStream> response = send(uri);
        int status = response.statusCode();
        if (status >= 300 && status < 400) {
            throw badRequest("TEMPLATE_MEDIA_LINK_REDIRECTED",
                    "这个地址会跳转到别处（" + status + "）。请给一个最终的图片地址 —— "
                            + "跟着跳转走会把安全检查绕过一跳");
        }
        if (status != 200) {
            throw badRequest("TEMPLATE_MEDIA_LINK_UNREACHABLE",
                    "这个地址没能取到图片（HTTP " + status + "）");
        }
        byte[] bytes = readBounded(response.body());
        return new Fetched(bytes, sniff(bytes), fileNameOf(uri));
    }

    private URI requireHttpsUri(String rawUrl) {
        URI uri;
        try {
            uri = new URI(rawUrl);
        } catch (URISyntaxException | NullPointerException e) {
            throw badRequest("TEMPLATE_MEDIA_LINK_INVALID", "这不是一个地址：" + rawUrl);
        }
        String scheme = uri.getScheme();
        if (!"https".equalsIgnoreCase(scheme)) {
            // scheme 为 null 是「整段话没有协议头」的情形。这时渲染「收到「null」」对用户毫无意义，
            // 所以只说清楚要什么 —— 这句措辞会一路走到模型嘴里，再被念给用户听。
            throw badRequest("TEMPLATE_MEDIA_LINK_INVALID",
                    "只接受 https 开头的图片地址" + (scheme == null ? "" : "，收到「" + scheme + "」"));
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw badRequest("TEMPLATE_MEDIA_LINK_INVALID", "这个地址里没有主机名：" + rawUrl);
        }
        return uri;
    }

    /**
     * 主机名必须是域名，且解析出来的每一个地址都是公网地址。
     *
     * <p>两步都要：第一步挡「{@code http://127.0.0.1:8080}」这种直接写地址的，
     * 第二步挡「一个指向内网的域名」—— 没有第一步，第二步会被 {@code 169.254.169.254}
     * 这种连 DNS 都不用走的写法绕过。
     */
    private void requirePublicHost(String host) {
        String normalized = host.toLowerCase(Locale.ROOT);
        if (normalized.matches(IPV4_LITERAL) || normalized.contains(":")) {
            throw badRequest("TEMPLATE_MEDIA_LINK_INVALID",
                    "图片地址要用域名，不能直接写 IP（" + host + "）");
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw badRequest("TEMPLATE_MEDIA_LINK_UNREACHABLE", "这个域名解析不了：" + host);
        }
        for (InetAddress address : addresses) {
            if (!isPublicAddress(address)) {
                // 不把解析出的地址写进给用户的措辞里：那等于让这个接口帮忙做内网 DNS 探测。
                log.warn("template media link rejected: host {} resolves into a private address", host);
                throw badRequest("TEMPLATE_MEDIA_LINK_INVALID",
                        "这个域名指向的是一个内网地址，不能作为图片地址");
            }
        }
    }

    private static boolean isPublicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int first = bytes[0] & 0xff;
            int second = bytes[1] & 0xff;
            // 0.0.0.0/8、100.64.0.0/10（运营商级 NAT）、192.0.0.0/24、198.18.0.0/15、224.0.0.0/4。
            return first != 0 && !(first == 100 && second >= 64 && second <= 127)
                    && !(first == 192 && second == 0 && (bytes[2] & 0xff) == 0)
                    && !(first == 198 && (second == 18 || second == 19))
                    && first < 224;
        }
        // IPv6 里没有覆盖到的唯一本地地址 fc00::/7（fe80::/10 已由 isLinkLocalAddress 拦下）。
        return (bytes[0] & 0xfe) != 0xfc;
    }

    private HttpResponse<InputStream> send(URI uri) {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", ACCEPT)
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw badRequest("TEMPLATE_MEDIA_LINK_UNREACHABLE", "这个地址打不开（" + e.getClass().getSimpleName() + "）");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw badRequest("TEMPLATE_MEDIA_LINK_UNREACHABLE", "抓取这张图片时被中断，请重试");
        }
    }

    /**
     * 读字节，读到上限就断。
     *
     * <h2>为什么不先看 {@code Content-Length}</h2>
     * 那个头是对方填的：可以缺、可以说 1KB 实际发 1GB、也可以说 1GB 实际发 1KB。
     * 它能用来做「提前拒绝」，不能用来决定「要不要读完」—— 唯一可信的边界是我们自己数出来的字节数。
     *
     * <p>超限时<b>抛出</b>而不是「截断后用」：半个 JPEG 上传上去是一张永远显示不出来的图，
     * 而且它要到模板审核那一步才会被发现。
     */
    private static byte[] readBounded(InputStream body) {
        long maxBytes = WhatsAppTemplateMediaUploadService.IMAGE_MAX_BYTES;
        ByteArrayOutputStream output = new ByteArrayOutputStream(8192);
        byte[] buffer = new byte[8192];
        long total = 0;
        try (InputStream input = body) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) {
                    throw badRequest("TEMPLATE_MEDIA_LINK_TOO_LARGE",
                            "这张图片超过了 " + (maxBytes / 1024 / 1024) + "MB 的上限");
                }
                output.write(buffer, 0, read);
            }
        } catch (IOException e) {
            throw badRequest("TEMPLATE_MEDIA_LINK_UNREACHABLE", "读到一半连接断了，请重试");
        }
        if (total == 0) {
            throw badRequest("TEMPLATE_MEDIA_LINK_UNREACHABLE", "这个地址返回了空内容");
        }
        return output.toByteArray();
    }

    /**
     * 从文件头判类型。
     *
     * <p>与 {@code WhatsAppTemplateMediaUploadService#mediaLimit} 同源：那里对 IMAGE 只放行
     * {@code image/png} 与 {@code image/jpeg}，所以这里也只可能返回这两个之一 ——
     * 别的格式在这里就被拦，给出一句「这不是 PNG/JPEG 图片」而不是让它到上传链路里
     * 撞成一句「contentType 不允许」。
     */
    private static String sniff(byte[] bytes) {
        if (startsWith(bytes, PNG_MAGIC)) return "image/png";
        if (startsWith(bytes, JPEG_MAGIC)) return "image/jpeg";
        throw badRequest("TEMPLATE_MEDIA_LINK_NOT_IMAGE",
                "这个地址返回的不是 PNG 或 JPEG 图片");
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        if (bytes.length < prefix.length) return false;
        for (int index = 0; index < prefix.length; index++) {
            if (bytes[index] != prefix[index]) return false;
        }
        return true;
    }

    /**
     * 给服务商用的文件名。
     *
     * <p>它来源于地址的最后一段，是<b>不可信输入</b>，所以只留
     * {@code [A-Za-z0-9._-]} 并限长：这个字符串会被拼进对象键，而对象键是我们拼的。
     * 取不到像样的名字时用 {@code image} —— 服务商并不靠它认类型（类型是文件头判的）。
     */
    private static String fileNameOf(URI uri) {
        String path = uri.getPath() == null ? "" : uri.getPath();
        String name = path.substring(path.lastIndexOf('/') + 1).replaceAll("[^A-Za-z0-9._-]", "_");
        if (name.isBlank() || name.equals("_")) {
            return "image";
        }
        return name.length() > 100 ? name.substring(0, 100) : name;
    }

    private static WhatsAppTemplateException badRequest(String code, String message) {
        return new WhatsAppTemplateException(code, HttpStatus.BAD_REQUEST, message, Map.of(), null, false);
    }
}
