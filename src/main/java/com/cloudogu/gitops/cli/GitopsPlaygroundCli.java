package com.cloudogu.gitops.cli;

import static com.cloudogu.gitops.config.ConfigConstants.APP_NAME;
import static com.cloudogu.gitops.utils.MapUtils.deepMerge;
import static com.cloudogu.gitops.utils.MapUtils.deepMergeDefaults;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.encoder.Encoder;
import com.cloudogu.gitops.application.Application;
import com.cloudogu.gitops.config.Config;
import com.cloudogu.gitops.config.schema.JsonSchemaValidator;
import com.cloudogu.gitops.destroy.Destroyer;
import com.cloudogu.gitops.infrastructure.kubernetes.api.K8sClient;
import com.cloudogu.gitops.tools.common.CommonToolConfig;
import com.cloudogu.gitops.tools.common.Tool;
import groovy.yaml.YamlSlurper;
import io.micronaut.context.ApplicationContext;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.BiConsumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

@RequiredArgsConstructor
@Slf4j
public class GitopsPlaygroundCli {

  private static final String STDOUT_APPENDER_NAME = "STDOUT";

  private final K8sClient k8sClient;
  private final ApplicationConfigurator applicationConfigurator;

  public GitopsPlaygroundCli() {
    this(new K8sClient(), new ApplicationConfigurator());
  }

  public ReturnCode run(String[] args) {
    setLogging(args);

    log.debug("Reading initial CLI params");
    Config cliParams = new Config();
    new CommandLine(cliParams).parseArgs(args);

    if (Boolean.TRUE.equals(cliParams.getApplication().getUsageHelpRequested())) {
      new CommandLine(cliParams).execute(args);
      return ReturnCode.SUCCESS;
    }

    String version = createVersionOutput();
    if (Boolean.TRUE.equals(cliParams.getApplication().getVersionInfoRequested())) {
      System.out.println(version);
      return ReturnCode.SUCCESS;
    }

    ApplicationContext context = createApplicationContext();
    Application app = context.getBean(Application.class);

    Config config = readConfigs(args);
    runHook(app, "preConfigInit", Tool::preConfigInit, config);

    if (Boolean.TRUE.equals(config.getApplication().getOutputConfigFile())) {
      System.out.println(config.toYaml(false));
      return ReturnCode.SUCCESS;
    }

    config = applicationConfigurator.initConfig(config);
    log.debug("Actual config: {}", config.toYaml(true));
    runHook(app, "postConfigInit", Tool::postConfigInit, config);

    context.close();
    context = createApplicationContext();
    register(config, context);

    if (Boolean.TRUE.equals(config.getApplication().getDestroy())) {
      log.info(version);
      if (!confirm(
          "Destroying gitops playground in kubernetes cluster '"
              + k8sClient.getCurrentContext()
              + "'.",
          config)) {
        return ReturnCode.NOT_CONFIRMED;
      }

      Destroyer destroyer = context.getBean(Destroyer.class);
      destroyer.destroy();
    } else {
      log.info(version);
      if (!confirm(
          "Applying gitops playground to kubernetes cluster '"
              + k8sClient.getCurrentContext()
              + "'.",
          config)) {
        return ReturnCode.NOT_CONFIRMED;
      }
      app = context.getBean(Application.class);
      app.start();

      printWelcomeScreen(config.getApplication().getPassword());
    }

    return ReturnCode.SUCCESS;
  }

  protected String createVersionOutput() {
    String versionName = Version.NAME.replace("\\n", "\n");

    if (versionName.trim().startsWith("(")) {
      versionName = versionName.trim().replace("(", "").replace(")", "");
    }
    return APP_NAME + " " + versionName;
  }

  protected void register(Config config, ApplicationContext context) {
    context.registerSingleton(config);
  }

  private static boolean confirm(String message, Config config) {
    log.debug(
        "Calling confirm for message: {} | yes = {} | System.in class: {}",
        message,
        config.getApplication().getYes(),
        System.in.getClass().getName());
    if (Boolean.TRUE.equals(config.getApplication().getYes())) {
      return true;
    }

    log.info("\n{}\nContinue? y/n [n]", message);

    try {
      BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
      String input = reader.readLine();
      return "y".equals(input);
    } catch (IOException e) {
      throw new RuntimeException("Failed to read user input", e);
    }
  }

  protected ApplicationContext createApplicationContext() {
    return ApplicationContext.run();
  }

  private void setLogging(String[] args) {
    Logger logger = (Logger) LoggerFactory.getLogger("com.cloudogu.gitops");
    List<String> argList = Arrays.asList(args);
    if (argList.contains("--trace") || argList.contains("-x")) {
      log.info("Setting loglevel to trace");
      logger.setLevel(Level.TRACE);
      System.setProperty("picocli.trace", "DEBUG");
    } else if (argList.contains("--debug") || argList.contains("-d")) {
      System.setProperty("picocli.trace", "INFO");
      logger.setLevel(Level.DEBUG);
      log.info("Setting loglevel to debug");
    } else {
      setSimpleLogPattern();
    }
  }

  public void setSimpleLogPattern() {
    LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
    Logger rootLogger = loggerContext.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    Appender<ILoggingEvent> stdoutAppender = rootLogger.getAppender(STDOUT_APPENDER_NAME);
    if (!(stdoutAppender instanceof ConsoleAppender)) {
      return;
    }
    Encoder<ILoggingEvent> encoderObj = ((ConsoleAppender) stdoutAppender).getEncoder();
    if (!(encoderObj instanceof PatternLayoutEncoder)) {
      return;
    }

    String defaultPattern = ((PatternLayoutEncoder) encoderObj).getPattern();

    rootLogger.detachAppender(STDOUT_APPENDER_NAME);
    PatternLayoutEncoder encoder = new PatternLayoutEncoder();
    encoder.setPattern(
        defaultPattern.replaceAll(" \\S*%thread\\S* ", " ").replaceAll(" \\S*%logger\\S* ", " "));
    encoder.setContext(loggerContext);
    encoder.start();
    ConsoleAppender<ILoggingEvent> appender = new ConsoleAppender<>();
    appender.setName(STDOUT_APPENDER_NAME);
    appender.setContext(loggerContext);
    appender.setEncoder(encoder);
    appender.start();
    rootLogger.addAppender(appender);
  }

  private Config readConfigs(String[] args) {
    Config cliParams = new Config();
    new CommandLine(cliParams).parseArgs(args);

    Config profileConfig = extractProfile(cliParams);

    List<Map<String, Object>> configFile = new ArrayList<>();
    List<Map<String, Object>> configMap = new ArrayList<>();

    if (cliParams.getApplication().getConfigFiles() != null) {
      for (String configFileItem : cliParams.getApplication().getConfigFiles()) {
        log.debug("Reading config file {}", configFileItem);
        try {
          configFile.add(validateConfig(Files.readString(Path.of(configFileItem))));
        } catch (IOException e) {
          throw new RuntimeException("Failed to read config file: " + configFileItem, e);
        }
      }
    }

    if (cliParams.getApplication().getConfigMaps() != null) {
      for (String configMapItem : cliParams.getApplication().getConfigMaps()) {
        log.debug("Reading config map {}", configMapItem);
        String configValues = k8sClient.getConfigMap(configMapItem, "config.yaml");
        configMap.add(validateConfig(configValues));
      }
    }

    Map<String, Object> mergedConfigs = new HashMap<>();
    deepMerge(profileConfig.toMap(), mergedConfigs);
    for (Map<String, Object> map : configMap) {
      deepMerge(map, mergedConfigs);
    }
    for (Map<String, Object> map : configFile) {
      deepMerge(map, mergedConfigs);
    }

    mergedConfigs = deepMergeDefaults(mergedConfigs, new Config().toMap());

    log.debug("Writing CLI params into config");
    log.debug(
        "mergedConfigs keys: {} | application map: {}",
        mergedConfigs.keySet(),
        mergedConfigs.get("application"));
    Config mergedConfig = Config.fromMap(mergedConfigs);
    log.debug(
        "mergedConfig yes before parseArgs: {}",
        mergedConfig.getApplication() != null ? mergedConfig.getApplication().getYes() : "null");
    new CommandLine(mergedConfig).parseArgs(args);
    log.debug(
        "mergedConfig yes after parseArgs: {}",
        mergedConfig.getApplication() != null ? mergedConfig.getApplication().getYes() : "null");

    return mergedConfig;
  }

  @SuppressWarnings("unchecked")
  public static Map<String, Object> validateConfig(String configValues) {
    Object map = new YamlSlurper().parseText(configValues);
    if (!(map instanceof Map)) {
      throw new RuntimeException("Could not parse YAML as map: " + map);
    }
    JsonSchemaValidator.validate((Map<?, ?>) map);
    return (Map<String, Object>) map;
  }

  public void printWelcomeScreen(String password) {
    log.info(
        """

                  |----------------------------------------------------------------------------------------------|
                  |                       Welcome to the GitOps playground by Cloudogu!
                  |----------------------------------------------------------------------------------------------|
                  |
                  | Please find the URLs of the individual applications in our README:
                  | https://github.com/cloudogu/gitops-playground/blob/main/README.md#table-of-contents
                  |
                  | A good starting point might also be the services or ingresses inside your cluster: \s
                  | kubectl get svc -A
                  | Or (depending on your config)
                  | kubectl get ing -A
                  |
                  | Please be aware, Jenkins and Argo CD may take some time to build and deploy all apps.
                  |\s
                  | Your initial password for all apps (if not set manually): %s
                  |\s
                  |----------------------------------------------------------------------------------------------|
                """
            .formatted(password));
  }

  public static void runHook(
      Application app, String hookName, BiConsumer<Tool, Config> hook, Config config) {
    List<Tool> allFeatures = new ArrayList<>();
    allFeatures.add(new CommonToolConfig());
    allFeatures.addAll(app.getTools());

    for (Tool feature : allFeatures) {
      try {
        log.debug("Executing {} hook on feature {}", hookName, feature.getClass().getName());
        hook.accept(feature, config);
      } catch (Exception e) {
        throw new RuntimeException(
            "Failed to execute hook " + hookName + " on " + feature.getClass().getName(), e);
      }
    }
  }

  private static Config extractProfile(Config newConfig) {
    String profile = newConfig.getApplication().getProfile();

    Config profileConfig = new Config();
    if (profile != null && !profile.isEmpty()) {
      String resourceName = "application-" + profile + ".yaml";
      log.debug("Loading profile '{}' from classpath", resourceName);

      try (InputStream inputStream =
          GitopsPlaygroundCli.class.getResourceAsStream("/" + resourceName)) {
        if (inputStream == null) {
          throw new RuntimeException(
              "Profile '"
                  + profile
                  + "' does not exist (resource '"
                  + resourceName
                  + "' not found).");
        }
        String content =
            new String(inputStream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        Map<String, Object> profileFile = validateConfig(content);
        profileConfig = Config.fromMap(profileFile);
      } catch (IOException e) {
        throw new RuntimeException("Failed to read profile " + profile, e);
      }
    }
    return profileConfig;
  }
}
