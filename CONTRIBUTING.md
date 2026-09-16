# Contributing

Thanks for taking the time to contribute! This document describes the conventions we
follow so that the codebase stays consistent and reviews stay fast. Please read it
before opening a pull request.

## Table of Contents

- [Language](#language)
- [Branching Strategy](#branching-strategy)
- [Commit Guidelines](#commit-guidelines)
- [Pull Requests](#pull-requests)
- [Code Style (Java)](#code-style-java)
- [Testing](#testing)
- [Code Review Etiquette](#code-review-etiquette)

## Language

- Write all code and comments in English.
- Project documentation is written in German. (This CONTRIBUTING guide is in English,
  following the convention of the platform it's hosted on.)

## Branching Strategy

- `develop` contains the latest state of development.
- `main` reflects the current release.
- Use `feature/<description>` branches for new functionality
  (e.g. `feature/add-user-authentication`).
- Use `fix/<description>` branches for bug fixes in existing code
  (e.g. `fix/login-button-not-working`).
- Use `hotfix/<description>` branches for critical fixes against `main`
  (e.g. `hotfix/security-patch-for-cve-2024-1234`).

## Commit Guidelines

- **Write small, focused commits.** Each commit should contain a single, logical
  change. Avoid bundling unrelated changes together.
- **Commit frequently.** Frequent commits with clear messages make rollbacks and
  history easier to follow.
- **Write meaningful commit messages.** The diff already shows *what* changed; the
  commit message should explain *why*. Write it so an outside developer can
  understand it without additional context.

  ```
  # bad
  "Add method validate"

  # good
  "Validate feature configuration before enabling to prevent runtime errors in production"
  ```

- **Write commit messages in English.**
- **Use semantic versioning for tags.** Follow [SemVer](https://semver.org/) for
  release tags (e.g. `v1.0.0`, `v2.3.1`).
- **Run a linter before committing or pushing** (e.g. Checkstyle or PMD) to catch
  style issues and common bugs automatically.

## Pull Requests

- **Keep PRs small.** Large pull requests are hard to merge and either stall in
  review or get rubber-stamped without a real look.
- **Provide context in the description.** Explain the goal of the PR and how the
  change can be tested — it helps reviewers understand the code faster.
- **Ensure CI is green** before requesting review and before merging.
- **Use descriptive PR titles.** PR titles are often used to generate changelogs, so
  avoid vague titles like "fix bug" or "refactoring". Prefer precise titles such as
  `fix: resolve memory leak in session management`.
- **Prefer merge commits (`--no-ff`) over squash** when merging, so the full history
  of development steps stays traceable. Squashing is acceptable for hotfixes or small
  changes to keep the history clean.

## Code Style (Java)

- Use `camelCase` for variable and method names, `PascalCase` for class and enum
  names, and tabs for indentation.
- Use descriptive names for variables and methods — verbose, speaking names help the
  reader understand code faster and should reveal *what* a method does, not *how*.
- Use explicit typing; avoid overusing `var`. Only use `var` when the type is already
  obvious from the right-hand side (e.g. `var client = new HttpClientFactory()`);
  spell out the type when it comes from a method call or generic expression whose
  return type isn't visible at the call site.

  ```java
  // bad
  var result = repository.find(id);

  // good
  Optional<Repository> result = repository.find(id);
  ```

- Use named lambda parameters instead of single letters, especially in nested streams.

  ```java
  // bad
  users.stream().filter(u -> u.isActive()).forEach(u -> u.sendNotification());

  // good
  users.stream()
      .filter(user -> user.isActive())
      .forEach(activeUser -> activeUser.sendNotification());
  ```

- Use `Optional<T>` only for genuinely optional values — reserve it for values that
  are legitimately absent (e.g. a lookup that may find nothing), and use
  `Objects.requireNonNull()` / fail-fast validation for values that must always be
  present. Don't wrap required fields in `Optional` just to avoid a null check. Once a
  value is an `Optional<T>`, unwrap it with `orElse`/`orElseGet` (or a ternary for
  plain nullable references) rather than calling `.get()` behind a null check.

  ```java
  // bad (address must always be present)
  String zip = user.getAddress() == null ? null : user.getAddress().getZipCode();

  // good (address is genuinely optional)
  Optional<Address> address = user.getAddress();
  String zip = address.map(Address::getZipCode).orElse(null);
  ```

  ```java
  // bad
  String name = user.getName() != null ? user.getName() : "Default";

  // good (Optional-based)
  String name = Optional.ofNullable(user.getName()).orElse("Default");
  ```

- Prefer builders over long constructors once a type has more than 2-3 fields, so call
  sites read like named arguments (we use Lombok's `@Builder`). Whenever a fluent
  chain (builder or otherwise) exceeds 2-3 calls, put each call on its own line for
  readability.

  ```java
  // bad
  Config config = new Config(host, port, true, false, null, retries);
  config.setHost("localhost").setPort(8080).setEnabled(true).setDebug(false);

  // good
  Config config = Config.builder()
      .host(host)
      .port(port)
      .enabled(true)
      .build();

  config.setHost("localhost")
      .setPort(8080)
      .setEnabled(true)
      .setDebug(false);
  ```

- Use comments to explain *why* code does something, not *what* it does. What the
  code does should already be self-explanatory.

  ```java
  // bad
  // set retry to 3
  int retryCount = 3;

  // good
  // we use 3 retries because the external API is unstable
  int retryCount = 3;
  ```

- **Fail fast** and **use defensive programming** — validate inputs up front and
  return safe defaults instead of propagating `null`.

  ```java
  // fail fast
  void processOrder(Order order) {
    if (order == null) {
      throw new IllegalArgumentException("Order must not be null");
    }
    // ... logic
  }

  // defensive programming
  List<String> getTags(User user) {
    if (user.getTags() == null) {
      return List.of();
    }
    return user.getTags();
  }
  ```

- Keep classes and methods small and focused, following the single responsibility
  principle.

  ```java
  // bad
  class OrderManager {
    void processOrder(Order order) { /* ... */ }
    void sendEmail(String recipient, String message) { /* ... */ }
    void saveToDatabase(Order order) { /* ... */ }
  }

  // good
  class OrderService {
    void processOrder(Order order) { /* ... */ }
  }

  class EmailService {
    void sendEmail(String recipient, String message) { /* ... */ }
  }
  ```

- Use the right exceptions. Prefer specific exceptions over the generic
  `RuntimeException`/`Exception`, and create custom exception classes where the
  context calls for it.

  ```java
  // bad
  throw new RuntimeException("Order not found");

  // good
  throw new OrderNotFoundException("Order with ID " + orderId + " not found");
  ```

- Avoid deeply nested code. Use guard clauses and fail-fast to keep nesting depth low.

  ```java
  // bad
  void process(User user) {
    if (user != null) {
      if (user.isActive()) {
        // ... a lot of logic
      }
    }
  }

  // good
  void process(User user) {
    if (user == null || !user.isActive()) {
      return;
    }
    // ... a lot of logic
  }
  ```

- Obey the boy scout rule: "Always leave the campground cleaner than you found it."
  When you touch a file, fix small messes (typos, formatting) in the immediate area of
  your change.

- Prefer text blocks / `String.format` over ad hoc concatenation when building
  strings — plain literals for static text, `String.format(...)` or text blocks
  (`"""..."""`, Java 15+) when you actually need to build a string from parts.

  ```java
  // good
  String constant = "I am a static string";
  String dynamic = String.format("I am dynamic: %s", constant);
  ```

- Avoid runtime metaprogramming and reflection. Reflection-based frameworks and
  dynamic proxies bypass compile-time type checking, hurt performance, and are
  fragile at runtime. Prefer direct API calls, interfaces, or
  composition/polymorphism.

  ```java
  // bad
  Method method = UserService.class.getDeclaredMethod("doSomething");
  method.invoke(userService);

  // good
  userService.doSomething();
  ```

- Use `@Slf4j` for logging. Lombok's `@Slf4j` annotation injects a
  `private static final Logger log` field — don't hand-declare a `Logger` via
  `LoggerFactory.getLogger(...)` for a class's own logging. (A deliberately-named,
  cross-cutting logger not tied to the enclosing class name is a legitimate
  exception, since `@Slf4j` can only produce a logger named after the class.)

  ```java
  // bad
  import org.slf4j.Logger;
  import org.slf4j.LoggerFactory;

  class UserService {
    private static final Logger log = LoggerFactory.getLogger(UserService.class);
  }

  // good
  import lombok.extern.slf4j.Slf4j;

  @Slf4j
  class UserService {
    void doSomething() {
      log.info("Doing something...");
    }
  }
  ```

- Use uniform logging levels, consistently and deliberately, to keep log volume and
  readability sane in production:
  - `debug` / `trace`: detailed diagnostic info for development-time troubleshooting
    (e.g. method parameters, loop iterations).
  - `info`: important, business-critical or systemic milestones (e.g. successful
    startup, completed transaction). Don't overuse.
  - `warn`: unexpected situations that don't block the flow (e.g. fallbacks, use of
    deprecated APIs, transient connection errors).
  - `error`: errors that require aborting or manual intervention (e.g. caught
    exceptions, system failures).

## Testing

We use JUnit 5 and Mockito.

- Use descriptive test class names (e.g. `UserServiceTest`).
- Structure tests with given-when-then / arrange-act-assert comments.
- Use `@ParameterizedTest` (with `@ValueSource`, `@CsvSource` or `@MethodSource`) for
  data-driven tests.
- Mock external dependencies using Mockito's `@Mock` / `Mockito.mock(...)`.

```java
class CalculatorTest {

  private final Calculator calculator = new Calculator();

  @ParameterizedTest
  @CsvSource({
      "5, 10, 15",
      "0, 0, 0",
      "-3, 3, 0"
  })
  void shouldCalculateSumCorrectly(int a, int b, int expected) {
    // when
    int result = calculator.add(a, b);

    // then
    assertEquals(expected, result);
  }
}
```

```java
@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

  @Mock
  private OrderRepository orderRepository;

  @InjectMocks
  private OrderService orderService;

  @Test
  void shouldThrowWhenOrderNotFound() {
    // given
    when(orderRepository.findById("123")).thenReturn(Optional.empty());

    // when / then
    assertThrows(OrderNotFoundException.class, () -> orderService.getOrder("123"));
  }
}
```

## Code Review Etiquette

- **Be constructive and respectful.** Criticize the code, not the author. Phrase
  suggestions as questions or ideas (e.g. "Have you considered...?" instead of
  "This is wrong").
- **Praise good code.** If you see a particularly elegant solution, say so — reviews
  are also a place to learn and to give positive feedback.
