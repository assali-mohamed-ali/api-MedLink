package com.example.apimedlink;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Review on #123: every Terraform variable without a default must be in each checked-in tfvars example and in the
 * README's required-settings table, so a plan from any example never stops on a missing required variable.
 */
class TerraformExamplesTest {

    private static final Path TERRAFORM = Path.of("terraform");
    private static final Pattern VARIABLE = Pattern.compile("(?ms)^variable \"(\\w+)\" \\{(.*?)^}");
    private static final List<String> EXAMPLES = List.of(
            "terraform.tfvars.example", "terraform.tfvars.staging.example", "terraform.tfvars.prod.example");

    @Test
    void everyRequiredVariableIsInEveryExampleAndInTheReadme() throws IOException {
        List<String> required = requiredVariables();
        assertThat(required).contains("report_encryption_key", "mfa_encryption_key");

        for (String example : EXAMPLES) {
            String text = Files.readString(TERRAFORM.resolve(example));
            for (String name : required) {
                assertThat(text).as(example + " sets " + name).containsPattern("(?m)^" + name + "\\s*=");
            }
        }
        String readme = Files.readString(TERRAFORM.resolve("README.md"));
        for (String name : required) {
            assertThat(readme).as("README.md lists " + name).contains("`" + name + "`");
        }
    }

    private static List<String> requiredVariables() throws IOException {
        Matcher variable = VARIABLE.matcher(Files.readString(TERRAFORM.resolve("variables.tf")));
        List<String> names = new ArrayList<>();
        while (variable.find()) {
            if (!variable.group(2).contains("default")) {
                names.add(variable.group(1));
            }
        }
        return names;
    }
}
