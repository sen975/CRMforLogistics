package com.crmforlogistics.messagecenter;

import com.crmforlogistics.messagecenter.config.CallRecordConfig;
import com.crmforlogistics.messagecenter.config.FunAsrConfig;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.stereotype.Controller;

@Configuration
@EnableAutoConfiguration
@ComponentScan(
        basePackages = "com.crmforlogistics.messagecenter",
        excludeFilters = {
                @ComponentScan.Filter(type = FilterType.ANNOTATION, classes = Controller.class),
                @ComponentScan.Filter(type = FilterType.ANNOTATION, classes = ControllerAdvice.class),
                @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = App.class)
        })
@ConfigurationPropertiesScan
@EnableConfigurationProperties({CallRecordConfig.class, FunAsrConfig.class})
public class InstallationImportApplication {
}
