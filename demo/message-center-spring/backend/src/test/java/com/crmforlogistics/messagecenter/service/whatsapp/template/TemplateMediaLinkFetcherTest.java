package com.crmforlogistics.messagecenter.service.whatsapp.template;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 收图前的几道门。
 *
 * <p>这个类是本项目里唯一一处服务端<b>主动访问用户给的地址</b>的地方（见被测算的类注释），
 * 所以这里逐条钉住「什么样的地址根本不该被访问」。
 *
 * <h2>为什么这些用例不依赖网络也能算数</h2>
 * 它们全部命中的是<b>发出请求之前</b>的检查：协议、主机名形状、解析结果。
 * 真实抓取那一段（连上去、读字节、判文件头）没有在这里覆盖 ——
 * 它需要外网，而一个会因网络抖动的红灯比不覆盖更糟。那一段的事实依据记在
 * 项目文档的实测记录里（一次真实 https 图片地址、一次死域名、一次非 URL 的对照）。
 */
class TemplateMediaLinkFetcherTest {

    private final TemplateMediaLinkFetcher fetcher = new TemplateMediaLinkFetcher();

    @Test
    void aPlainHttpAddressIsRejected() {
        assertThat(codeOf("http://cdn.example.com/quote.png")).isEqualTo("TEMPLATE_MEDIA_LINK_INVALID");
    }

    /**
     * 回环与私有网段<b>按字面量</b>直接拒。
     *
     * <p>这是 SSRF 最常见的写法：不用域名，直接写地址。内网服务的地址几乎总是长这样，
     * 所以「拒掉所有 IP 字面量」比逐个比对网段更难写错 —— 下面这四个网段只是顺带确认。
     */
    @Test
    void ipLiteralsAreRejected() {
        assertThat(codeOf("https://127.0.0.1/quote.png")).isEqualTo("TEMPLATE_MEDIA_LINK_INVALID");
        assertThat(codeOf("https://10.0.0.7/quote.png")).isEqualTo("TEMPLATE_MEDIA_LINK_INVALID");
        assertThat(codeOf("https://192.168.1.10/quote.png")).isEqualTo("TEMPLATE_MEDIA_LINK_INVALID");
        // 云厂商的实例元数据服务：一次成功的访问就能拿到临时凭证。
        assertThat(codeOf("https://169.254.169.254/latest/meta-data/")).isEqualTo("TEMPLATE_MEDIA_LINK_INVALID");
        // IPv6 字面量在 URI 里带方括号，主机名里会出现冒号。
        assertThat(codeOf("https://[::1]/quote.png")).isEqualTo("TEMPLATE_MEDIA_LINK_INVALID");
    }

    /** 名字解析到回环也算：挡的不是「写了什么」，而是「最终会连到哪里」。 */
    @Test
    void localhostIsRejectedBecauseItResolvesToALoopback() {
        assertThat(codeOf("https://localhost/quote.png")).isEqualTo("TEMPLATE_MEDIA_LINK_INVALID");
    }

    @Test
    void aBareWordIsNotAnAddress() {
        assertThat(codeOf("not-a-url")).isEqualTo("TEMPLATE_MEDIA_LINK_INVALID");
        assertThat(codeOf("ftp://cdn.example.com/quote.png")).isEqualTo("TEMPLATE_MEDIA_LINK_INVALID");
    }

    @Test
    void anAddressWithoutAHostIsRejected() {
        assertThat(codeOf("https:///quote.png")).isEqualTo("TEMPLATE_MEDIA_LINK_INVALID");
    }

    /**
     * 拒绝理由必须带出来。
     *
     * <p>这些措辞会一路走到工具的 observation 里，再被模型念给用户 ——
     * 一句「图片地址不行」用户没法改，一句「只接受 https 开头的图片地址」他立刻知道怎么办。
     */
    @Test
    void theRefusalSaysWhatToChange() {
        assertThat(messageOf("http://cdn.example.com/quote.png")).contains("https");
        assertThat(messageOf("https://10.0.0.7/quote.png")).contains("域名");
    }

    private String codeOf(String url) {
        try {
            fetcher.fetch(url);
            return "（没有被拒）";
        } catch (WhatsAppTemplateException error) {
            return error.code();
        }
    }

    private String messageOf(String url) {
        try {
            fetcher.fetch(url);
            return "（没有被拒）";
        } catch (WhatsAppTemplateException error) {
            return error.getMessage();
        }
    }
}
