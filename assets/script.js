function setup() {
    const storageKey = "theme";
    const root = document.documentElement;
    const state = {
        manifest: null,
        version: "",
        language: "",
        page: ""
    };

    function preferredTheme() {
        const saved = localStorage.getItem(storageKey);
        if (saved === "dark" || saved === "light") return saved;
        return window.matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light";
    }

    function applyTheme(theme) {
        root.classList.toggle("dark", theme === "dark");
        root.dataset.theme = theme;
    }

    window.toggleTheme = function toggleTheme() {
        const nextTheme = root.classList.contains("dark") ? "light" : "dark";
        localStorage.setItem(storageKey, nextTheme);
        applyTheme(nextTheme);
    };

    function manifestPath() {
        const script = document.currentScript || document.querySelector("script[src$='script.js']");
        const src = script ? script.getAttribute("src") || "" : "";
        return src.includes("assets/script.js") ? "/manifest.json" : "../manifest.json";
    }

    async function loadManifest() {
        console.log("manifest loading from path : ", manifestPath());
        const response = await fetch(manifestPath());
        if (!response.ok) throw new Error(`Unable to load manifest.json (${response.status})`);
        return response.json();
    }

    function readRoute() {
        const manifest = state.manifest;
        const segments = window.location.pathname
            .replace(/\/index\.html$/, "")
            .replace(/^\/+|\/+$/g, "")
            .split("/")
            .filter(Boolean)
            .map(decodeURIComponent);

        if (segments.length >= 2 && manifest.versions?.includes(segments[0])) {
            return {
                version: segments[0],
                language: segments[1],
                page: normalizePage(segments.slice(2).join("/") || manifest.defaultPage)
            };
        }

        if (segments.length > 0 && segments[0] !== "assets") {
            return {
                version: manifest.defaultVersion,
                language: manifest.defaultLanguage,
                page: normalizePage(segments.join("/"))
            };
        }

        return {
            version: manifest.defaultVersion,
            language: manifest.defaultLanguage,
            page: normalizePage(manifest.defaultPage)
        };
    }

    function writeRoute(push = true) {
        const pagePath = state.page.replace(/\.md$/, "");
        const path = `/${[state.version, state.language, pagePath].filter(Boolean).map(encodePathSegment).join("/")}`;
        const url = `${path}${window.location.hash || ""}`;
        if (push) {
            window.history.pushState({...state}, "", url);
        } else {
            window.history.replaceState({...state}, "", url);
        }
    }

    function normalizePage(page) {
        if (!page) return "index.md";
        const clean = page.replace(/^\/+|\/+$/g, "");
        return clean.endsWith(".md") ? clean : `${clean}.md`;
    }

    function pageKey(version = state.version, language = state.language, page = state.page) {
        return `${version}/${language}/${page}`;
    }

    function setSelectOptions(select, values, selectedValue) {
        if (!select) return;
        select.innerHTML = values.map((value) => {
            const selected = value === selectedValue ? " selected" : "";
            return `<option value="${escapeAttribute(value)}"${selected}>${escapeHtml(value)}</option>`;
        }).join("");
    }

    function populateSwitchers() {
        const manifest = state.manifest;
        setSelectOptions(document.querySelector("#versionSelect"), manifest.versions || [], state.version);
        setSelectOptions(document.querySelector("#langSelect"), manifest.languages || [], state.language);
    }

    function renderSidebar() {
        const sidebar = document.querySelector(".sidebar");
        if (!sidebar || !state.manifest) return;

        const tree = state.manifest.trees?.[`${state.version}/${state.language}`] || [];
        sidebar.innerHTML = `
            <div class="sidebar-inner">
                ${tree.length ? renderNavItems(tree) : `<p class="empty-panel">No pages in this language.</p>`}
            </div>
        `;
    }

    function renderNavItems(items) {
        return `<ul class="nav-tree">${items.map(renderNavItem).join("")}</ul>`;
    }

    function renderNavItem(item) {
        if (item.type === "group") {
            return `
                <li class="nav-group">
                    <details open>
                        <summary>${escapeHtml(item.title)}</summary>
                        ${renderNavItems(item.children || [])}
                    </details>
                </li>
            `;
        }

        const active = item.path === state.page ? " is-active" : "";
        return `
            <li>
                <a class="${active.trim()}" href="${routeFor(state.version, state.language, item.path)}" data-doc-page="${escapeAttribute(item.path)}">
                    ${escapeHtml(item.title)}
                </a>
            </li>
        `;
    }

    async function renderPage() {
        const page = state.manifest.pages?.[pageKey()];
        populateSwitchers();
        renderSidebar();
        closePanels();

        if (!page) {
            renderMissingPage();
            writeRoute(false);
            bindLinks();
            return;
        }

        try {
            const response = await fetch(`/${page.contentPath}`);
            if (!response.ok) throw new Error(`Unable to load ${page.contentPath}`);
            const content = await response.json();
            renderContent(content);
            renderRightSidebar(content);
            document.title = `${content.title} | KodeDocs`;
            if (isVersionedPath()) writeRoute(false);
            bindLinks();
            scrollToHash();
        } catch (error) {
            console.error(error);
            renderErrorPage(page);
        }
    }

    function renderContent(content) {
        const main = document.querySelector("main");
        if (!main) return;
        main.classList.add("docs-content", "vp-doc");
        const authors = Array.isArray(content.metadata?.authors) ? content.metadata.authors : [];
        main.innerHTML = `
            <h1>${escapeHtml(content.title)}</h1>
            ${content.description ? `<p class="lead">${escapeHtml(content.description)}</p>` : ""}
            ${authors.length ? `
                <section class="main-authors" aria-label="Page authors">
                    <span>Authors</span>
                    <div class="authors">${authors.map(renderAuthor).join("")}</div>
                </section>
            ` : ""}
            ${content.html}
        `;
    }

    function renderRightSidebar(content) {
        const aside = document.querySelector(".right-sidebar");
        const railToc = document.querySelector("[data-rail-toc]");
        if (!aside) return;

        const headings = (content.headings || []).filter((heading) => heading.level >= 2 && heading.level <= 4);
        const tocHtml = headings.length ? `
            <section class="toc-section">
                <div class="toc-marker"></div>
                <div class="toc-title">On This Page</div>
                ${renderTOC(headings)}
            </section>
        ` : `<p class="empty-panel">No sections on this page.</p>`;

        aside.innerHTML = `
            <div class="toc-inner">
                ${tocHtml}
            </div>
        `;
        if (railToc) railToc.innerHTML = tocHtml;
    }

    function renderTOC(headings) {
        if (!headings.length) return "";

        let toc = [];
        let currentLevel = 0;
        let first = true;

        headings.forEach((heading) => {
            let level = heading.level;

            if (first) {
                toc.push("<ul>");
                currentLevel = level;
                first = false;
            }

            if (level > currentLevel) {
                toc.push("<ul class='toc-sub'>");
            } else if (level === currentLevel) {
                toc.push("</li>");
            } else {
                while (level < currentLevel) {
                    toc.push("</li></ul>");
                    currentLevel--;
                }
                toc.push("</li>");
            }

            toc.push(`
                <li ${heading.level !== 2 ? 'class="toc-level"' : ''}>
                    <a href="#${escapeAttribute(heading.id)}">
                        ${escapeHtml(heading.text)}
                    </a>
            `);

            currentLevel = level;
        });

        while (currentLevel > 0) {
            toc.push("</li></ul>");
            currentLevel--;
        }

        return toc.join("");
    }

    function renderAuthor(author) {
        const username = String(author).replace(/[^a-zA-Z0-9_-]/g, "");
        if (!username) return "";
        return `
            <a href="https://github.com/${escapeAttribute(username)}" target="_blank" rel="noreferrer" class="author" title="${escapeAttribute(username)}">
                <img src="https://github.com/${escapeAttribute(username)}.png" alt="${escapeAttribute(username)}">
            </a>
        `;
    }

    function renderMissingPage() {
        const main = document.querySelector("main");
        const aside = document.querySelector(".right-sidebar");
        const existsInVersion = Object.keys(state.manifest.pages || {}).some((key) => {
            return key.startsWith(`${state.version}/`) && key.endsWith(`/${state.page}`);
        });
        const title = existsInVersion ? "Translation not available" : "Version page not available";
        const message = existsInVersion
            ? `The page ${state.page} exists, but it has no ${state.language} translation for ${state.version}.`
            : `The page ${state.page} is not available in ${state.version}.`;

        document.title = `${title} | KodeDocs`;
        if (main) {
            main.innerHTML = `
                <p class="doc-kicker">Missing content</p>
                <h1>${title}</h1>
                <p class="lead">${escapeHtml(message)}</p>
                <div class="callout">
                    <strong>What you can do:</strong> choose another language, select a different version, or open another page from the navigation.
                </div>
            `;
        }
        if (aside) {
            aside.innerHTML = `
                <div class="toc-inner">
                    <h2 class="toc-title">Page Status</h2>
                    <ul class="toc-list">
                        <li><a href="#">${escapeHtml(state.version)}</a></li>
                        <li><a href="#">${escapeHtml(state.language)}</a></li>
                    </ul>
                </div>
            `;
        }
        const railToc = document.querySelector("[data-rail-toc]");
        if (railToc) railToc.innerHTML = aside?.innerHTML || "";
    }

    function renderErrorPage(page) {
        const main = document.querySelector("main");
        if (!main) return;
        main.innerHTML = `
            <p class="doc-kicker">Load error</p>
            <h1>Could not load this page</h1>
            <p class="lead">The manifest points to ${escapeHtml(page.contentPath)}, but the content file could not be read.</p>
        `;
    }

    function changeRoute(next) {
        state.version = next.version || state.version;
        state.language = next.language || state.language;
        state.page = normalizePage(next.page || state.page);
        if (!next.keepHash) history.replaceState({...state}, "", window.location.pathname);
        writeRoute(true);
        renderPage();
    }

    function isVersionedPath() {
        const firstSegment = window.location.pathname
            .replace(/^\/+|\/+$/g, "")
            .split("/")
            .filter(Boolean)[0];
        return state.manifest?.versions?.includes(decodeURIComponent(firstSegment || "")) || false;
    }

    function bindLinks() {
        document.querySelectorAll("[data-doc-page]").forEach((anchor) => {
            anchor.onclick = (event) => {
                event.preventDefault();
                changeRoute({page: anchor.dataset.docPage});
            };
        });
    }

    function bindSwitchers() {
        const versionSelect = document.querySelector("#versionSelect");
        const langSelect = document.querySelector("#langSelect");

        if (versionSelect) {
            versionSelect.addEventListener("change", (event) => {
                changeRoute({version: event.target.value});
                closeControlsMenu();
            });
        }

        if (langSelect) {
            langSelect.addEventListener("change", (event) => {
                changeRoute({language: event.target.value});
                closeControlsMenu();
            });
        }
    }

    function closePanels() {
        document.body.classList.remove("panel-open");
        document.querySelectorAll(".sidebar.is-open, .right-sidebar.is-open").forEach((panel) => {
            panel.classList.remove("is-open");
        });
        document.querySelectorAll("[data-panel-toggle]").forEach((button) => {
            button.setAttribute("aria-expanded", "false");
        });
    }

    function openPanel(panelName, button) {
        const target = document.querySelector(`[data-panel="${panelName}"]`);
        if (!target) return;

        const willOpen = !target.classList.contains("is-open");
        closePanels();

        if (willOpen) {
            target.classList.add("is-open");
            document.body.classList.add("panel-open");
            button.setAttribute("aria-expanded", "true");
        }
    }

    function bindPanelToggles() {
        document.querySelectorAll("[data-panel-toggle]").forEach((button) => {
            button.addEventListener("click", () => openPanel(button.dataset.panelToggle, button));
        });

        const backdrop = document.querySelector(".mobile-backdrop");
        if (backdrop) {
            backdrop.addEventListener("click", () => {
                closePanels();
                closeControlsMenu();
            });
        }

        document.addEventListener("keydown", (event) => {
            if (event.key === "Escape") {
                closePanels();
                closeControlsMenu();
            }
        });
    }

    function bindControlsMenu() {
        const toggle = document.querySelector("[data-controls-toggle]");
        const controls = document.querySelector(".controls");
        if (!toggle || !controls) return;

        toggle.addEventListener("click", () => {
            const isOpen = document.body.classList.toggle("controls-open");
            toggle.setAttribute("aria-expanded", String(isOpen));
        });

        controls.addEventListener("click", (event) => {
            if (event.target.closest("button, a")) {
                closeControlsMenu();
            }
        });
    }

    function closeControlsMenu() {
        document.body.classList.remove("controls-open");
        document.querySelector("[data-controls-toggle]")?.setAttribute("aria-expanded", "false");
    }

    function routeFor(version, language, page) {
        const pagePath = normalizePage(page).replace(/\.md$/, "");
        return `/${[version, language, pagePath].filter(Boolean).map(encodePathSegment).join("/")}`;
    }

    function encodePathSegment(segment) {
        return String(segment).split("/").map(encodeURIComponent).join("/");
    }

    function scrollToHash() {
        if (!window.location.hash) return;
        requestAnimationFrame(() => {
            const id = decodeURIComponent(window.location.hash.slice(1));
            document.getElementById(id)?.scrollIntoView();
        });
    }

    function escapeHtml(value) {
        return String(value)
            .replace(/&/g, "&amp;")
            .replace(/</g, "&lt;")
            .replace(/>/g, "&gt;")
            .replace(/"/g, "&quot;")
            .replace(/'/g, "&#039;");
    }

    function escapeAttribute(value) {
        return escapeHtml(value);
    }

    window.addEventListener("popstate", () => {
        const route = readRoute();
        state.version = route.version;
        state.language = route.language;
        state.page = route.page;
        renderPage();
    });

    document.addEventListener("DOMContentLoaded", async () => {
        applyTheme(preferredTheme());
        bindPanelToggles();
        bindControlsMenu();
        bindSwitchers();

        try {
            console.log("manifest loading now...");
            state.manifest = await loadManifest();
            const route = readRoute();
            state.version = route.version;
            state.language = route.language;
            state.page = route.page;
            await renderPage();
        } catch (error) {
            console.error(error);
            const main = document.querySelector("main");
            if (main) {
                main.innerHTML = `
                    <p class="doc-kicker">Preview shell</p>
                    <h1>KodeDocs site preview</h1>
                    <p class="lead">Generate the site to create manifest.json and content files for this shell.</p>
                `;
            }
        }
    });
}

setup();
