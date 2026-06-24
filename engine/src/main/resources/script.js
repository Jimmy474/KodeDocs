function setup() {
    const storageKey = "theme";
    const root = document.documentElement;
    const state = {
        manifest: null,
        version: "",
        language: "",
        page: "",
        markerCleanup: null,
        markerLockId: "",
        markerLockTimer: null
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

    async function loadManifest() {
        const response = await fetch("/manifest.json");
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
            window.history.pushState(routeState(), "", url);
        } else {
            window.history.replaceState(routeState(), "", url);
        }
    }

    function routeState() {
        return {
            version: state.version,
            language: state.language,
            page: state.page
        };
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
        renderSiteConfig();
    }

    function renderSiteConfig() {
        const manifest = state.manifest;
        if (!manifest || !manifest.site) return;

        const site = manifest.site;
        
        // Branding
        if (site.brand) {
            const brandLogo = document.querySelector(".brand-logo");
            const brandMark = document.querySelector(".brand-mark");
            const brandText = document.querySelector(".brand-text");
            
            if (brandLogo) {
                if (site.brand.logoImage) {
                    brandLogo.innerHTML = `<img src="${escapeAttribute(site.brand.logoImage)}" alt="${escapeAttribute(site.brand.name || "")}" class="logo-image">`;
                    if (brandMark) brandMark.textContent = "";
                } else {
                    brandLogo.innerHTML = "";
                    if (brandMark) brandMark.textContent = site.brand.logoText || (site.brand.name ? site.brand.name.charAt(0) : "");
                }
            }
            if (brandText) brandText.textContent = site.brand.name || "";
        }

        // Navigation
        const topNav = document.querySelector(".top-nav");
        if (topNav && site.nav) {
            topNav.innerHTML = site.nav.map(item => {
                return `<a href="${escapeAttribute(item.link)}">${escapeHtml(item.text)}</a>`;
            }).join("");
            bindLinks();
            updateTopNavActive();
        }
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
        if (item.type === "Group") {
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
            const content = await loadPageContent(page);
            renderContent(content);
            renderRightSidebar(content);
            document.title = `${content.title} | KodeDocs`;
            if (isVersionedPath()) writeRoute(false);
            bindLinks();
            bindAdmonitionToggles();
            scrollToHash();
        } catch (error) {
            renderErrorPage(page);
        }
    }

    async function loadPageContent(page) {
        const response = await fetch(`/${page.contentPath}`);
        if (!response.ok) throw new Error(`Unable to load ${page.contentPath}`);
        const html = await response.text();
        const documentFragment = new DOMParser().parseFromString(html, "text/html");
        const metaNode = documentFragment.querySelector("[data-page-meta]");
        const meta = metaNode ? JSON.parse(metaNode.textContent || "{}") : {};
        metaNode?.remove();

        return {
            title: meta.title || page.title,
            description: meta.description || page.description || "",
            metadata: meta.metadata || {},
            headings: meta.headings || [],
            html: documentFragment.body.innerHTML
        };
    }

    function renderContent(content) {
        const main = document.querySelector("main");
        if (!main) return;
        main.classList.add("docs-content", "kd-doc");
        main.innerHTML = content.html;
    }

    function renderRightSidebar(content) {
        const aside = document.querySelector(".right-sidebar");
        const railToc = document.querySelector("[data-rail-toc]");
        if (!aside) return;

        const authors = Array.isArray(content.metadata?.authors) ? content.metadata.authors : [];
        const headings = (content.headings || []).filter((heading) => heading.level >= 2 && heading.level <= 4);
        const renderedToc = renderTOC(headings);
        const tocHtml = headings.length ? `
            <section class="toc-section">
                <div class="toc-marker"></div>
                <div class="toc-title">On This Page</div>
                ${renderedToc}
            </section>
            <section class="sidebar-authors" aria-label="Page authors">
                <div class="authors-title">PAGE AUTHORS</div>
                <div class="authors">${authors.map(renderAuthor).join("")}</div>
            </section>
        ` : `<p class="empty-panel">No sections on this page.</p>`;

        aside.innerHTML = `
            <div class="toc-inner">
                ${tocHtml}
            </div>
        `;
        if (railToc) {
            railToc.innerHTML = `
                <section class="rail-toc-section">
                    <div class="toc-title">On This Page</div>
                    ${renderedToc}
                </section>
            `;
        }
        bindTocLinks();
        setupTocMarker();
    }

    function renderTOC(headings) {
        if (!headings.length) return "";

        let toc = [];
        let currentLevel = 0;
        let first = true;

        headings.forEach((heading) => {
            let level = heading.level;

            if (first) {
                toc.push("<ul class='toc-top-ul'>");
                currentLevel = level;
                first = false;
            }

            if (level > currentLevel) {
                toc.push("<ul>");
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
                <li class="toc-level">
                    <a href="#${escapeAttribute(heading.id)}" title="${escapeHtml(heading.text)}">
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
        if (railToc) {
            railToc.innerHTML = aside?.innerHTML || "";
            railToc.querySelector(".toc-marker")?.remove();
        }
    }

    function renderErrorPage(page) {
        const main = document.querySelector("main");
        if (!main) return;
        main.innerHTML = `
            <p class="doc-kicker">Load error</p>
            <h1>Could not load this page</h1>
            <p class="lead">The manifest points to ${escapeHtml(page.contentPath)}, but the page HTML could not be read.</p>
        `;
    }

    function changeRoute(next) {
        state.version = next.version || state.version;
        state.language = next.language || state.language;
        state.page = normalizePage(next.page || state.page);
        if (!next.keepHash) history.replaceState(routeState(), "", window.location.pathname);
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
        document.querySelectorAll("[data-doc-page], .top-nav a").forEach((anchor) => {
            anchor.onclick = (event) => {
                const href = anchor.getAttribute("href");
                if (href && (href.startsWith("/") || href.startsWith("http"))) {
                    // Check if it matches a doc page
                    const manifest = state.manifest;
                    const segments = href.replace(/^\/+|\/+$/g, "").split("/");
                    if (segments.length >= 3 && manifest.versions?.includes(segments[0])) {
                        event.preventDefault();
                        changeRoute({
                            version: segments[0],
                            language: segments[1],
                            page: segments.slice(2).join("/")
                        });
                        return;
                    }
                }
                
                if (anchor.dataset.docPage) {
                    event.preventDefault();
                    changeRoute({page: anchor.dataset.docPage});
                }
            };
        });
    }

    function bindAdmonitionToggles() {
        document.querySelectorAll(".admonition.collapsable").forEach((admonition) => {
            admonition.addEventListener("click", (event) => {
                admonition.classList.toggle("open");
                admonition.classList.toggle("closed");
            })
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

    function updateTopNavActive() {
        const currentPath = window.location.pathname;
        document.querySelectorAll(".top-nav a").forEach((link) => {
            const href = link.getAttribute("href");
            if (href && href !== "#") {
                const isActive = currentPath.startsWith(href);
                link.classList.toggle("active", isActive);
                link.setAttribute("aria-current", isActive ? "page" : "false");
            }
        });
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
            document.getElementById(id)?.scrollIntoView({ behavior: "smooth" });
            requestAnimationFrame(updateTocMarker);
        });
    }

    function setupTocMarker() {
        if (state.markerCleanup) state.markerCleanup();

        const toc = document.querySelector(".right-sidebar .toc-section");
        const tocLinks = [...(toc?.querySelectorAll("a[href^='#']") || [])];
        const headings = getContentHeadings();

        if (!toc || !tocLinks.length || !headings.length) {
            state.markerCleanup = null;
            return;
        }

        const onScroll = rafThrottle(updateTocMarker);
        window.addEventListener("scroll", onScroll, { passive: true });
        window.addEventListener("resize", onScroll);
        window.addEventListener("hashchange", onScroll);
        state.markerCleanup = () => {
            window.removeEventListener("scroll", onScroll);
            window.removeEventListener("resize", onScroll);
            window.removeEventListener("hashchange", onScroll);
        };

        requestAnimationFrame(updateTocMarker);
    }

    function updateTocMarker() {
        const toc = document.querySelector(".right-sidebar .toc-section");
        const links = [...(toc?.querySelectorAll("a[href^='#']") || [])];
        const headings = getContentHeadings();
        if (!toc || !links.length || !headings.length) return;

        if (state.markerLockId) {
            setActiveTocId(state.markerLockId);
            return;
        }

        const offset = getStickyOffset() + 36;
        let activeHeading = headings[0];

        for (const heading of headings) {
            if (heading.getBoundingClientRect().top <= offset) {
                activeHeading = heading;
            } else {
                break;
            }
        }

        const activeId = headingId(activeHeading);
        setActiveTocId(activeId);
    }

    function setActiveTocId(activeId) {
        const toc = document.querySelector(".right-sidebar .toc-section");
        const links = [...(toc?.querySelectorAll("a[href^='#']") || [])];
        if (!toc || !links.length || !activeId) return;

        const activeLink = links.find((link) => decodeURIComponent(link.hash.slice(1)) === activeId) || links[0];
        links.forEach((link) => link.classList.toggle("active", link === activeLink));
    }

    function bindTocLinks() {
        document.querySelectorAll(".right-sidebar .toc-section a[href^='#'], [data-rail-toc] a[href^='#']").forEach((link) => {
            link.addEventListener("click", (event) => {
                event.preventDefault();
                const id = decodeURIComponent(link.hash.slice(1));
                const target = document.getElementById(id);

                lockTocMarker(id);
                if (target) target.scrollIntoView({ behavior: "smooth", block: "start" });
                history.replaceState(routeState(), "", `${window.location.pathname}#${encodeURIComponent(id)}`);
            });
        });
    }

    function lockTocMarker(id) {
        state.markerLockId = id;
        setActiveTocId(id);

        if (state.markerLockTimer) clearTimeout(state.markerLockTimer);
        state.markerLockTimer = setTimeout(() => {
            state.markerLockId = "";
        }, 900);
    }

    function getStickyOffset() {
        const header = document.querySelector(".site-header")?.offsetHeight || 0;
        const rail = getComputedStyle(document.querySelector(".collapsed-rail") || document.body).display !== "none"
            ? document.querySelector(".collapsed-rail")?.offsetHeight || 0
            : 0;
        return header + rail;
    }

    function getContentHeadings() {
        return [...document.querySelectorAll(".kd-doc h2, .kd-doc h3, .kd-doc h4")]
            .filter((heading) => headingId(heading));
    }

    function headingId(heading) {
        return heading.id || heading.querySelector("[id]")?.id || "";
    }

    function rafThrottle(callback) {
        let ticking = false;
        return () => {
            if (ticking) return;
            ticking = true;
            requestAnimationFrame(() => {
                callback();
                ticking = false;
            });
        };
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
            state.manifest = await loadManifest();
            const route = readRoute();
            state.version = route.version;
            state.language = route.language;
            state.page = route.page;
            await renderPage();
        } catch (error) {
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
