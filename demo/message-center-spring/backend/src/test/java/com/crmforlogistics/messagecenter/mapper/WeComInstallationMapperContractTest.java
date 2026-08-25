package com.crmforlogistics.messagecenter.mapper;

import org.apache.ibatis.annotations.Param;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Parameter;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class WeComInstallationMapperContractTest {
    @Test
    void activeInstallationLookupNamesBothSqlParametersExplicitly() throws Exception {
        Parameter[] parameters = WeComInstallationMapper.class
                .getMethod("findActive", String.class, String.class)
                .getParameters();

        assertThat(Arrays.stream(parameters)
                .map(parameter -> parameter.getAnnotation(Param.class))
                .map(Param::value))
                .containsExactly("suiteId", "authCorpId");
    }
}
