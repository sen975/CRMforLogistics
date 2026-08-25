package com.crmforlogistics.messagecenter.config;

import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.core.annotation.AnnotatedElementUtils;

import static org.assertj.core.api.Assertions.assertThat;

class MyBatisConfigurationContractTest {

    @Test
    void mapperPackageHasSingleScanOwner() {
        assertThat(AnnotatedElementUtils.hasAnnotation(MyBatisPlusConfig.class, MapperScan.class))
                .isTrue();
        assertThat(AnnotatedElementUtils.hasAnnotation(MyBatisMapperConfig.class, MapperScan.class))
                .isFalse();
    }
}
