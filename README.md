# KodeDocs

KodeDocs is a documentation generator that supports versioning, translation, and live preview.

## Building the Project

To build the project, run the following command from the root directory:

```powershell
.\gradlew.bat build
```

## Running the Preview Server

The preview server builds the documentation and starts a local server to preview the site with live reload.

To run the server, use:

```powershell
.\gradlew.bat :app:run
```

Once the server is running:
- You can access the site at `http://localhost:8080`.
- Any changes made to the `docs/` directory or the code snippets in `app/src/main/resources/` will trigger an automatic rebuild and refresh the browser.

## Project Structure

- `docs/`: Contains the documentation source files in Markdown format.
  - `latest/en/`: The latest English version of the docs.
  - `<version>/<lang>/`: Specific versions and languages.
- `app/`: The application module containing the preview server and the main entry point.
- `engine/`: The core logic for processing Markdown and code snippets.
- `build/site/`: The generated static site.

## Kodedocs Snippets

You can inject code snippets into your Markdown files using the following syntax:

```markdown
`​``kodedocs
file: app/src/main/resources/test.java
include: main
exclude: temp
`​``
```

- `file`: Path to the source file.
- `include`: Comma-separated list of `#region` names to include.
- `exclude`: Comma-separated list of `#region` names to exclude.