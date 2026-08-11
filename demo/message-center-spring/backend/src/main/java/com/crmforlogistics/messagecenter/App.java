package com.crmforlogistics.messagecenter;

import com.crmforlogistics.messagecenter.config.CallRecordConfig;
import com.crmforlogistics.messagecenter.config.FunAsrConfig;
import com.crmforlogistics.messagecenter.service.auth.BootstrapService;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableConfigurationProperties({CallRecordConfig.class, FunAsrConfig.class})
public class App {
    public static void main(String[] args) {
        if (args.length > 0 && "bootstrap-admin".equals(args[0])) {
            var ctx = SpringApplication.run(App.class, args);
            var bootstrap = ctx.getBean(BootstrapService.class);
            var result = bootstrap.bootstrap();
            System.out.println("{\"created\":" + result.created() + ",\"userId\":\"" + result.userId() + "\",\"code\":\"" + result.code() + "\"}");
            System.exit(SpringApplication.exit(ctx));
            return;
        }
        SpringApplication.run(App.class, args);
    }
}
