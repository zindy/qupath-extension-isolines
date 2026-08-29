# QuPath Extension Generator

This Gradle task makes it super easy to create new QuPath extensions from the template without manually renaming everything!

## Setup (One-time)

1. Add the `create-extension.gradle.kts` file to your `qupath-extension-template` directory
2. Add this line at the end of your `build.gradle.kts`:

```kotlin
apply(from = "create-extension.gradle.kts")
```

That's it! You're ready to create extensions.

---

## Workflows

There are two ways to use this tool depending on how you want to host your extension.

### Workflow A: GitHub Template → In-Place (Recommended)

This is the cleanest approach if you want your extension to live in its own GitHub repository from the start.

1. On GitHub, click **"Use this template" → "Create a new repository"** and name it following the QuPath convention, e.g. `qupath-extension-my-awesome`
2. Clone your new repository locally:
   ```bash
   git clone https://github.com/yourname/qupath-extension-my-awesome
   cd qupath-extension-my-awesome
   ```
3. Run the generator **in-place** — the extension name is inferred automatically from the folder name:
   ```bash
   ./gradlew createExtension -PinPlace
   ```
4. Commit and push the result:
   ```bash
   git add -A
   git commit -m "Initialize MyAwesome from qupath-extension-template"
   git push
   ```
5. Build and install:
   ```bash
   ./gradlew build
   # Drag build/libs/qupath-extension-my-awesome-*.jar onto QuPath
   ```

The in-place run renames all source files and packages, updates all build files, and then **deletes the scaffolding script and this README** — leaving a clean, ready-to-code repository. The `.git`, `.github`, `.gradle`, and `build` directories are never touched.

> **Tip:** If you forget `-PinPlace` while already inside the matching folder, the script will tell you:
> ```
> You are already inside 'qupath-extension-my-awesome'. Did you mean to run with -PinPlace?
> ```

---

### Workflow B: Generate a Sibling Directory

Use this if you want to keep the template in place and generate new extensions next to it.

```bash
# Java-only extension (default)
./gradlew createExtension -PextensionName=MyAwesome

# Groovy-only extension
./gradlew createExtension -PextensionName=MyAwesome -Planguage=groovy

# Both Java and Groovy
./gradlew createExtension -PextensionName=MyAwesome -Planguage=both
```

A new directory named `qupath-extension-my-awesome` is created next to the template directory.

---

### Workflow C: Interactive Mode

Run without parameters to be guided through the options step by step. If your folder already follows the naming convention, the extension name is pre-filled for you:

```bash
./gradlew createExtension
```

```
Enter extension name [MyAwesome]:           ← press Enter to accept
Language (java/groovy/both) [java]:         ← press Enter to accept

Creating extension with:
  Extension name: MyAwesome
  Language:       java
  ...

Continue? (y/n): y
```

---

## Language Options

The `-Planguage` flag (or interactive prompt) controls which language support is included:

- **`java`** (default) — Java-only extension
  - Includes Java source files
  - Removes Groovy plugin and dependencies from `build.gradle.kts`
  - Deletes Groovy source files and empty directories

- **`groovy`** — Groovy-only extension
  - Includes Groovy source files and plugin
  - Removes Java source files

- **`both`** — Dual-language extension
  - Includes both Java and Groovy source files
  - Keeps all dependencies and plugins

**Why choose one over the other?**
- **Java**: Simpler, smaller extension if you don't need Groovy's scripting features
- **Groovy**: Use if you want Groovy's dynamic features or scripting support
- **Both**: Maximum flexibility, but larger dependency footprint

---

## What It Does

The task automatically:

1. **Renames all source files and packages**, e.g. for `MyAwesome`:

   | Before | After |
   |--------|-------|
   | `DemoExtension.java` | `MyAwesomeExtension.java` |
   | `DemoGroovyExtension.groovy` | `MyAwesomeGroovyExtension.groovy` |
   | `src/main/java/qupath/ext/template/` | `src/main/java/qupath/ext/myawesome/` |
   | `src/main/groovy/qupath/ext/template/` | `src/main/groovy/qupath/ext/myawesome/` |

2. **Replaces all occurrences** in text files:

   | Before | After |
   |--------|-------|
   | `qupath-extension-template` | `qupath-extension-my-awesome` |
   | `io.github.qupath.extension.template` | `io.github.qupath.extension.my-awesome` |
   | `qupath.ext.template` | `qupath.ext.myawesome` |
   | `DemoExtension` | `MyAwesomeExtension` |
   | `DemoGroovyExtension` | `MyAwesomeGroovyExtension` |
   | `Template` | `MyAwesome` |

3. **Updates build files** — `build.gradle.kts`, `settings.gradle.kts`, and the META-INF services file

4. **Cleans up based on language choice** — removes unwanted source files and strips Groovy plugin/dependencies if not needed

5. **Removes all empty directories** left behind after pruning

6. **In-place only:** deletes the scaffolding script (`create-extension.gradle.kts`) and this README once they are no longer needed

7. **Never touches** `.git`, `.github`, `.gradle`, or `build` directories

---

## Naming Conventions

The script follows QuPath's naming conventions:

- **CamelCase input**: `ProjectMetadataEditor`
- **Kebab-case** for artifacts/modules: `qupath-extension-project-metadata-editor`, `io.github.qupath.extension.project-metadata-editor`
- **Lowercase** for packages: `qupath.ext.projectmetadataeditor`
- **CamelCase** for class names: `ProjectMetadataEditorExtension`

---

## Examples

### In-place (from a GitHub template clone)

```
> Task :createExtension
  Inferred extension name from folder: MyAwesome

Creating extension with:
  Extension name: MyAwesome
  Language:       java
  Kebab-case:     my-awesome (for filenames)
  Lowercase:      myawesome (for packages)
  Package name:   qupath.ext.myawesome
  Artifact ID:    qupath-extension-my-awesome
  Module ID:      io.github.qupath.extension.my-awesome
  Mode:           in-place (modifying current repository)

Modifying extension in-place at: .../qupath-extension-my-awesome
  Updated: build.gradle.kts
  Updated: src\main\java\qupath\ext\template\DemoExtension.java
  Renamed: src\main\java\qupath\ext\template\DemoExtension.java -> src\main\java\qupath\ext\myawesome\MyAwesomeExtension.java
  Updated: src\main\java\qupath\ext\template\ui\InterfaceController.java
  Renamed: src\main\java\qupath\ext\template\ui\InterfaceController.java -> src\main\java\qupath\ext\myawesome\ui\InterfaceController.java
  Updated: src\main\resources\META-INF\services\qupath.lib.gui.extensions.QuPathExtension
  ...
  Deleted scaffolding file: create-extension.gradle.kts
  Deleted scaffolding file: README_CREATE-EXTENSION.md
  Deleted Groovy source (not included): src\main\groovy\qupath\ext\template\DemoGroovyExtension.groovy
  Removed empty dir: src\main\groovy
  ...

Repository converted in-place to: MyAwesome

Next steps:
  1. git add -A
  2. git commit -m "Initialize MyAwesome from qupath-extension-template"
  3. git push
  4. ./gradlew build
  5. Drag build/libs/qupath-extension-my-awesome-*.jar onto QuPath
```

### Sibling directory (Java-only)

```bash
$ ./gradlew createExtension -PextensionName=CellClassifier

Creating extension with:
  Extension name: CellClassifier
  Language:       java
  Kebab-case:     cell-classifier (for filenames)
  Lowercase:      cellclassifier (for packages)
  Package name:   qupath.ext.cellclassifier
  Artifact ID:    qupath-extension-cell-classifier
  Module ID:      io.github.qupath.extension.cell-classifier

Creating extension at: /path/to/qupath-extension-cell-classifier
  Updated: build.gradle.kts
  Renamed: src/main/java/qupath/ext/template/DemoExtension.java -> src/main/java/qupath/ext/cellclassifier/CellClassifierExtension.java
  Deleted Groovy source (not included): src/main/groovy/qupath/ext/template/DemoGroovyExtension.groovy
  Removed empty dir: src/main/groovy
  ...

Extension created successfully at: /path/to/qupath-extension-cell-classifier

Next steps:
  1. cd qupath-extension-cell-classifier
  2. ./gradlew build
  3. Drag build/libs/qupath-extension-cell-classifier-*.jar onto QuPath
```

### Sibling directory (Groovy-only)

```bash
$ ./gradlew createExtension -PextensionName=ScriptRunner -Planguage=groovy
```

### Sibling directory (both languages)

```bash
$ ./gradlew createExtension -PextensionName=Analyzer -Planguage=both
```

---

## Extension Name Rules

- Must start with an uppercase letter
- Can only contain letters and numbers
- Examples: `MyExtension`, `CellCounter`, `AwesomeTool`
- Invalid: `my-extension`, `123Tool`, `_MyExtension`

---

## Troubleshooting

**"You are already inside 'qupath-extension-my-awesome'. Did you mean to run with -PinPlace?"**
- You ran `createExtension` without `-PinPlace` from inside a folder whose name matches the inferred artifact ID. Add `-PinPlace` to modify the current repository.

**"Target directory already exists"**
- The extension was already created as a sibling directory. Delete it or use a different name.

**"Extension name must start with uppercase"**
- Use CamelCase naming, e.g. `MyExtension` not `myextension`

**"Invalid language 'xyz'"**
- Language must be one of: `java`, `groovy`, or `both`

**Build fails after generation**
- Make sure you're using Java 25 (as of QuPath v0.7.0)
- Run `./gradlew clean build` in the extension directory
- For Groovy extensions, ensure the Groovy plugin is properly configured

---

## Tips

- Use `java` (default) if you don't need Groovy — it produces a simpler, lighter extension
- Update the `version` in the template's `build.gradle.kts` before generating new extensions
- If your template has custom source files:
  - Java files should NOT contain "Groovy" in the filename
  - Groovy files should end in `.groovy` or contain "Groovy" in the filename
  - This ensures correct pruning when using language filters

## No More Manual Renaming! 🎉

You'll never have to spend hours finding and replacing "Template" and "template" again. Just click **"Use this template"**, clone, and run one command!