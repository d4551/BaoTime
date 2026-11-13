((origin, pageUrl, foreground, background, targetSize, spacing) => {
    const expectedPage = new URL(pageUrl);
    if (window !== window.top || location.origin !== origin || location.href !== expectedPage.href) {
        return false;
    }

    const screen = document.querySelector('.error-screen');
    const canvas = screen?.closest('.canvas');
    const footer = document.querySelector('footer.footer[role="contentinfo"]');
    const viewport = document.querySelector('meta[name="viewport"]');
    if (!screen?.querySelector('.error-container > h2.error-message') || !canvas ||
        screen.parentElement !== footer?.parentElement || !viewport) {
        return false;
    }

    if (document.querySelector('[data-baotime-error-style]')) return true;
    screen.setAttribute('role', 'main');
    screen.setAttribute('data-baotime-error-screen', '');
    footer.setAttribute('data-baotime-error-footer', '');
    canvas.setAttribute('data-baotime-error-canvas', '');
    screen.parentElement.setAttribute('data-baotime-error-content', '');

    const fixedScale = /^(?:minimum-scale|maximum-scale|user-scalable)\s*=/i;
    const viewportSettings = viewport.content.split(',').map(setting => setting.trim());
    viewport.content = viewportSettings.filter(setting => !fixedScale.test(setting)).join(',');

    const style = document.createElement('style');
    style.setAttribute('data-baotime-error-style', '');
    style.textContent = `
        .app-container > .canvas[data-baotime-error-canvas] {
            background-color: ${background};
            overflow: auto;
        }
        .canvas > [data-baotime-error-content] {
            display: flex;
            flex-direction: column;
            min-height: 100%;
        }
        .app-container > .canvas[data-baotime-error-canvas] [data-baotime-error-screen][role="main"] {
            height: auto;
            flex: 1 0 auto;
        }
        footer.footer[data-baotime-error-footer] {
            color: ${foreground};
            background-color: ${background};
        }
        footer.footer[data-baotime-error-footer] .links-section {
            display: flex;
            flex-wrap: wrap;
            justify-content: center;
            gap: ${spacing}px;
            overflow: visible;
        }
        footer.footer[data-baotime-error-footer] .link-wrapper::after {
            content: none;
        }
        footer.footer[data-baotime-error-footer] .links-section a.outbound-link,
        footer.footer[data-baotime-error-footer] .links-section ui-button.tou-button.primary.pill {
            box-sizing: border-box;
            display: inline-flex;
            align-items: center;
            justify-content: center;
            min-width: ${targetSize}px;
            min-height: ${targetSize}px;
            padding-inline: ${spacing}px;
            color: inherit;
            text-decoration: underline;
            white-space: normal;
        }
    `;
    document.head.append(style);

    const terms = footer.querySelector('.tou-button[aria-haspopup="dialog"]');
    const paneHost = canvas.closest('ui-main-pane')?.parentElement;
    if (!terms || !paneHost) return true;

    const updateTermsDialog = () => {
        const pane = paneHost.querySelector(':scope > ui-pane.legal-modal');
        const dialog = pane?.querySelector('ui-card[role="dialog"]');
        if (!dialog || location.href !== expectedPage.href || !screen.isConnected) return;

        if (pane.getAttribute('aria-modal') === 'true') {
            dialog.setAttribute('aria-modal', 'true');
            pane.removeAttribute('aria-modal');
        }
        const title = dialog.querySelector('.content h1');
        if (title?.textContent.trim()) {
            if (!title.id) title.id = 'baotime-facetime-terms-title';
            dialog.setAttribute('aria-labelledby', title.id);
            dialog.removeAttribute('aria-label');
            observeTerms.disconnect();
        } else if (!dialog.hasAttribute('aria-labelledby') && !dialog.hasAttribute('aria-label')) {
            dialog.setAttribute('aria-label', terms.textContent.trim());
        }
    };
    const observeTerms = new MutationObserver(updateTermsDialog);
    const watchTerms = () => {
        observeTerms.observe(paneHost, {childList: true, subtree: true});
        updateTermsDialog();
    };
    terms.addEventListener('click', watchTerms, {capture: true});
    if (paneHost.querySelector(':scope > ui-pane.legal-modal')) watchTerms();
    window.addEventListener('pagehide', () => observeTerms.disconnect(), {once: true});
    return true;
})
