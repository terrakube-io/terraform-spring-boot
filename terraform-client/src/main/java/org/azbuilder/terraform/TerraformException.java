package org.azbuilder.terraform;

public class TerraformException extends Exception{

    public TerraformException(String errorMessage){
        super(errorMessage);
    }
}
