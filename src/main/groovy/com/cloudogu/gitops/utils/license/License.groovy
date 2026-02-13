
package com.cloudogu.gitops.utils.license

import java.time.LocalDate
import java.time.format.DateTimeFormatter

class License {

    String name
    String product
    LocalDate expires
    List<String> features
    String version
    String signature

    boolean isExpired() {
        return expires != null && LocalDate.now().isAfter(expires)
    }

    boolean isValid() {
        return name && product && expires && !isExpired()
    }

    static License fromMap(Map<String, Object> json) {
        def license = new License()
        Map<String, Object> licenseData = json.license as Map<String, Object>

        if (licenseData) {
            license.name = licenseData.name as String
            license.product = licenseData.product as String
            license.version = licenseData.version as String
            license.features = licenseData.features as List<String>

            String expiresStr = licenseData.expires as String
            if (expiresStr) {
                license.expires = LocalDate.parse(expiresStr, DateTimeFormatter.ISO_DATE)
            }
        }

        license.signature = json.signature as String
        return license
    }
}