package com.crmforlogistics.messagecenter.architecture;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class AssistantToolAuthorizationArchitectureTest {

    @Test
    void assistantToolsMustUseDomainOwnersInsteadOfMappers() {
        var production = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.crmforlogistics.messagecenter");

        noClasses()
                .that().resideInAPackage("com.crmforlogistics.messagecenter.service.assistant.mcp..")
                .should().dependOnClassesThat().resideInAPackage("com.crmforlogistics.messagecenter.mapper..")
                .because("助手工具必须由领域 owner 执行授权读取")
                .check(production);
    }
}
