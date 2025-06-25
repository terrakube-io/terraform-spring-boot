package io.terrakube.terraform;

import lombok.Getter;

@Getter
public enum TerraformCommand {
    init("init"),
    plan("plan"),
    planDestroy("plan"),
    apply("apply"),
    destroy("destroy"),
    version("version"),
    show("show"),
    showPlan("show"),
    output("output"),
    statePull("state");

    private String label;

    TerraformCommand(String label){
        this.label = label;
    }
}
