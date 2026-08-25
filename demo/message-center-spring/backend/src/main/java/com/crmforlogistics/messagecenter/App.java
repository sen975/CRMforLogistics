package com.crmforlogistics.messagecenter;

import com.crmforlogistics.messagecenter.config.CallRecordConfig;
import com.crmforlogistics.messagecenter.config.FunAsrConfig;
import com.crmforlogistics.messagecenter.service.auth.BootstrapService;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Path;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableConfigurationProperties({CallRecordConfig.class, FunAsrConfig.class})
public class App {
    public static void main(String[] args) {
        if (args.length > 0 && AppCommand.IMPORT_INSTALL.equals(args[0])) {
            runInstallationImport(args);
            return;
        }
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

    private static void runInstallationImport(String[] args) {
        ConfigurableApplicationContext context = null;
        int exitCode = 0;
        try {
            Path file = AppCommand.importFile(args);
            SpringApplication application = new SpringApplication(InstallationImportApplication.class);
            application.setWebApplicationType(WebApplicationType.NONE);
            context = application.run(args);
            var service = context.getBean(
                    com.crmforlogistics.messagecenter.service.wecom.WeComInstallationImportService.class);
            var config = context.getBean(com.crmforlogistics.messagecenter.config.AppConfig.class);
            System.out.println(AppCommand.render(service.importFile(file, config.wecomSuiteId())));
        } catch (IllegalArgumentException | com.crmforlogistics.messagecenter.channel.wecom.WeComException exception) {
            exitCode = 1;
            System.err.println("企业微信安装导入失败: " + exception.getMessage());
        } finally {
            if (context != null) context.close();
        }
        if (exitCode != 0) System.exit(exitCode);
    }
}
