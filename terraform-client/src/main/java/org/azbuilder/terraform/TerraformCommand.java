package org.azbuilder.terraform;

import lombok.Getter;

@Getter
public enum TerraformCommand {
    init("init"),
    plan("plan"),
    apply("apply"),
    destroy("destroy"),
    version("version"),
    show("show"),
    showPlan("show");

    private String label;

    TerraformCommand(String label){
        this.label = label;
    }
}
