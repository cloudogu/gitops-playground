/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2020-2022 CROZ d.o.o, the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.cloudogu.gitops.graal.groovy;

import io.github.classgraph.ClassInfo;
import io.github.classgraph.ClassInfoList;
import org.graalvm.nativeimage.hosted.RuntimeReflection;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Properties;

/**
 *  Source: <a href="https://github.com/croz-ltd/klokwrk-project/tree/57202c58b792aff5f47e4c9033f91e5a31f100cc">...</a>
 */
public class RegistrationFeatureUtils {
    /**
     * Registers all supplied classes for runtime reflection.
     */
    public static void registerClasses(ClassInfoList classInfoToRegisterList) {
        classInfoToRegisterList
                .forEach((ClassInfo classInfo) -> {
                    try {
                        Class<?> someClass = Class.forName(classInfo.getName());
                        RuntimeReflection.register(someClass);
                        RuntimeReflection.register(someClass.getDeclaredConstructors());
                        RuntimeReflection.register(someClass.getDeclaredMethods());
                        RuntimeReflection.register(someClass.getDeclaredFields());
                    }
                    catch (ClassNotFoundException e) {
                        throw new RuntimeException(e);
                    }
                });
    }

    /**
     * Prints out (on err output stream) all elements of provided ClassInfoList.
     * <p/>
     * Intention is to use this method for diagnostic printouts. This is the reason why standard error output stream is used as ClassGraph also uses is for printing out diagnostic information in
     * verbose mode. This way outputs of ClassGraph and our own custom diagnostic does not mix and interfere.
     */
    public static void printClassInfoList(String classInfoListName, ClassInfoList classInfoList) {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("---------- ").append(classInfoListName).append("- start\n");

        for (ClassInfo classInfo : classInfoList) {
            stringBuilder.append(classInfo.toString()).append("\n");
        }

        stringBuilder.append("---------- ").append(classInfoListName).append(" - end\n");
        System.err.println(stringBuilder.toString());
    }

    /**
     * Loads and returns configuration properties.
     * <p/>
     * Returns null if properties file cannot be found or when properties are empty.
     */
    public static Properties loadKwrkGraalProperties(ClassLoader classLoader) {
        URL kwrkGraalPropertiesUrl = classLoader.getResource("kwrk-graal.properties");
        if (kwrkGraalPropertiesUrl == null) {
            return null;
        }

        Properties kwrkGraalConfig = new Properties();
        try (InputStream inputStream = kwrkGraalPropertiesUrl.openStream()) {
            kwrkGraalConfig.load(inputStream);
        }
        catch (IOException ioe) {
            throw new RuntimeException(ioe);
        }

        if (kwrkGraalConfig.isEmpty()) {
            return null;
        }

        return  kwrkGraalConfig;
    }
}
