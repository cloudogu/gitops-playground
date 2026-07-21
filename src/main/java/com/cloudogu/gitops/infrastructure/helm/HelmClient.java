package com.cloudogu.gitops.infrastructure.helm;

import com.cloudogu.gitops.utils.CommandExecutor;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Singleton
@RequiredArgsConstructor
@Slf4j
public class HelmClient {

  private final CommandExecutor commandExecutor;

  public String addRepo(String repoName, String url) {
    return helm(List.of("repo", "add", repoName, url));
  }

  public String dependencyBuild(String path) {
    return helm(List.of("dependency", "build", path));
  }

  public String upgrade(String release, String chartOrPath) {
    return upgrade(release, chartOrPath, Map.of());
  }

  public String upgrade(String release, String chartOrPath, Map<String, ?> args) {
    return helm(List.of("upgrade", "-i", release, chartOrPath, "--create-namespace"), args);
  }

  public String template(String release, String chartOrPath) {
    return template(release, chartOrPath, Map.of());
  }

  public String template(String release, String chartOrPath, Map<String, ?> args) {
    return helm(List.of("template", release, chartOrPath), args);
  }

  public String uninstall(String release, String namespace) {
    String[] command = {"helm", "uninstall", release, "--namespace", namespace};
    return commandExecutor.execute(command).getStdOut();
  }

  private String helm(List<String> verbAndParams) {
    return helm(verbAndParams, Map.of());
  }

  private String helm(List<String> verbAndParams, Map<String, ?> args) {
    List<String> command = new ArrayList<>();
    command.add("helm");
    command.addAll(verbAndParams);

    if (args != null) {
      for (Map.Entry<String, ?> entry : args.entrySet()) {
        String key = entry.getKey();
        Object value = entry.getValue();
        command.add("--" + key);
        command.add(value != null ? value.toString() : "");
      }
    }

    log.trace("Executing helm command: {}", String.join(" ", command));
    return commandExecutor.execute(command.toArray(new String[0])).getStdOut();
  }
}
