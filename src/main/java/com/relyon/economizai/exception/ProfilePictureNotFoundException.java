package com.relyon.economizai.exception;

public class ProfilePictureNotFoundException extends DomainException {

    public ProfilePictureNotFoundException() {
        super("profile.picture.not.found");
    }
}
