# KodeDocs

KodeDocs is a documentation generator that supports versioning, translation, and live preview.

## 📦 Installation & Prerequisites
This engine uses Project Panama and the native Tree-sitter C-library to achieve blazing-fast Markdown parsing. Because it talks directly to your hardware, you must install the Tree-sitter library on your operating system before running the site generator.

To Install Tree-sitter Choose your operating system below to install the native engine:

### 🍎 macOS (via Homebrew):

```bash
brew install tree-sitter
```

### 🐧 Linux (via APT):

```bash
sudo apt-get update
sudo apt-get install libtree-sitter-dev
```

### 🪟 Windows (via Scoop or Chocolatey):
If you are using Windows, we highly recommend installing via a package manager. Open your terminal and run one of the following:

```powershell
# If using Scoop
scoop install tree-sitter

# If using Chocolatey
choco install tree-sitter
```

## Kodedocs Snippets

You can inject code snippets into your Markdown files using the following syntax:

````markdown
```kodedocs
file: .../test.java
include: main
exclude: temp
```
````

- `file`: Path to the source file.
- `include`: Comma-separated list of `#region` names to include.
- `exclude`: Comma-separated list of `#region` names to exclude.