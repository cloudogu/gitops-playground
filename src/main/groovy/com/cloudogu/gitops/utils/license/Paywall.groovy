package com.cloudogu.gitops.utils.license

import groovy.util.logging.Slf4j

@Slf4j
class Paywall {

    Boolean isLicensed = false
    License license

    String licenseFile

    Paywall(String licenseFilePath) {
        readLicenseFile(licenseFilePath)
        this.license = LicenseValidator.parseLicense(this.licenseFile)
        this.isLicensed = (this.license != null)
        printPayWall()
    }

    void readLicenseFile(String licenseFilePath) {
        try {
            this.licenseFile = new File(licenseFilePath).text
        } catch (Exception exception) {
            log.error("Error parsing licenseFile!", exception)
        }
    }

    void printPayWall() {
        if (!isLicensed && isUpdate()) {
            String paywall = '''
==============================
  GOP is NOT licensed
==============================

This update requires a valid license.
Please contact sales@cloudogu.com

==============================
'''
            println paywall
            log.info("Shutdown!!")
            System.exit(1)
        }
        if (isLicensed) {
            log.info("License File is valid! {}", license)
        }
    }

    static boolean isUpdate() {
        //for testing
        return true
    }
}