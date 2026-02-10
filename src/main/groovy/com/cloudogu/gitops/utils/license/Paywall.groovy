package com.cloudogu.gitops.utils.license


class Paywall {

    Boolean isLicensed= false
    private static final String pubKeyFilePath='ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIDhgmxqVR7ie0L55RcTZ0+vyBu+48fFBjlsqKAp8TquL nhussi@nhussi'

    String licenseFile

    Paywall(String licenseFile){
        this.licenseFile=licenseFile
    }



    class LicenseGenerator {
        static void main(String[] args) {
        }

    }
}