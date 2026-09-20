package com.crmforlogistics.messagecenter.service.channel;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 渠道类型注册表 —— 「当前系统支持哪些渠道」的唯一真源。
 *
 * <p>把注入进来的 {@link ChannelType} 按 {@link ChannelType#key()} 与
 * {@link ChannelType#aliases()} 建索引，供核心按入参名字查表。核心因此不再需要
 * {@code switch(channelType)} 或写死的渠道白名单。
 *
 * <p>索引在构造时一次建成：渠道类型是启动期确定的，运行期不变，所以这里刻意用
 * 不可变 Map，查表无锁、无重复解析。
 */
@Service
public class ChannelTypeRegistry {

    private final Map<String, ChannelType> byName;
    private final Set<String> secretFields;

    public ChannelTypeRegistry(List<ChannelType> channelTypes) {
        Map<String, ChannelType> index = new HashMap<>();
        for (ChannelType channelType : channelTypes) {
            register(index, channelType.key(), channelType);
            channelType.aliases().forEach(alias -> register(index, alias, channelType));
        }
        this.byName = Map.copyOf(index);
        this.secretFields = channelTypes.stream()
                .flatMap(channelType -> channelType.secretFields().stream())
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * 按入参名字取渠道定义（大小写不敏感，别名同样命中）。
     *
     * @throws ChannelAccountException {@code CHANNEL_ACCOUNT_TYPE_UNSUPPORTED} 当名字未注册
     */
    public ChannelType require(String channelType) {
        ChannelType resolved = byName.get(trimmed(channelType).toLowerCase());
        if (resolved == null) {
            throw new ChannelAccountException("CHANNEL_ACCOUNT_TYPE_UNSUPPORTED", HttpStatus.BAD_REQUEST);
        }
        return resolved;
    }

    /** 所有已注册渠道声明过的敏感字段并集，用于凭证打码。 */
    public Set<String> secretFields() {
        return secretFields;
    }

    /**
     * 名称冲突直接让应用启动失败 —— 两个渠道抢同一个 key/别名是配置错误，
     * 静默覆盖会让其中一个渠道凭空消失，比启动失败难查得多。
     */
    private static void register(Map<String, ChannelType> index, String name, ChannelType channelType) {
        ChannelType previous = index.put(name.toLowerCase(), channelType);
        if (previous != null) {
            throw new IllegalStateException("Channel type name '" + name + "' is claimed by both "
                    + describe(previous) + " and " + describe(channelType));
        }
    }

    private static String describe(ChannelType channelType) {
        return channelType.getClass().getSimpleName() + "(key=" + channelType.key() + ")";
    }

    private static String trimmed(String value) {
        return value == null ? "" : value.trim();
    }
}
