// downloads.js — in-app downloads: progress panel, cancel, one download per file; saves without leaving the app
/*
 * A plain <a download> link gives no feedback inside an installed web app (no browser download bar),
 * and on iPhone it opens a full-screen preview with no way back. BYD.downloads.save() fetches the
 * file in the page instead, shows progress in a panel, and saves the finished file from there.
 *
 *   BYD.downloads.save(url, filename, { button })   // button (optional) shows a busy state
 *   BYD.downloads.save(url, '')                     // name taken from the server's Content-Disposition
 */
(function () {
    'use strict';
    window.BYD = window.BYD || {};

    // larger files are not held in memory: they fall back to the browser's own download
    var MAX_IN_MEMORY = 600 * 1024 * 1024;
    var DONE_HIDE_MS = 4000;

    var STRINGS = {
        tr: {
            downloading: 'İndiriliyor…', done: 'İndirildi', failed: 'İndirilemedi',
            cancel: 'İptal', retry: 'Tekrar dene', close: 'Kapat', cancelled: 'İndirme iptal edildi',
            already: 'Bu dosya zaten indiriliyor', direct: 'Büyük dosya: tarayıcı indiriyor',
            ready: 'Hazır. Kaydetmek için dokunun', save: 'Kaydet', saved: 'Kaydedildi',
            share_failed: 'Paylaşım menüsü açılamadı',
        },
        en: {
            downloading: 'Downloading…', done: 'Downloaded', failed: 'Download failed',
            cancel: 'Cancel', retry: 'Retry', close: 'Close', cancelled: 'Download cancelled',
            already: 'This file is already downloading', direct: 'Large file: the browser is downloading it',
            ready: 'Ready. Tap to save it', save: 'Save', saved: 'Saved',
            share_failed: 'The share sheet could not open',
        },
    };

    function lang() {
        var l = (window.BYD.i18n && typeof BYD.i18n.getLang === 'function' && BYD.i18n.getLang())
            || document.documentElement.lang || navigator.language || 'en';
        return String(l).toLowerCase().indexOf('tr') === 0 ? 'tr' : 'en';
    }
    function t(key) { return STRINGS[lang()][key] || STRINGS.en[key] || key; }

    function toast(msg, type) {
        if (window.BYD.core && BYD.core.toast) BYD.core.toast(msg, type || 'info');
        else if (window.BYD.utils && BYD.utils.toast) BYD.utils.toast(msg, type || 'info');
    }

    function mb(bytes) { return (bytes / 1048576).toFixed(1) + ' MB'; }

    // ── panel ──

    var STYLE = ''
        // plain values first: older WebViews drop the min()/env() declarations
        + '.dl-panel{position:fixed;left:50%;transform:translateX(-50%);z-index:9500;width:calc(100% - 24px);max-width:440px;'
        + 'bottom:80px;bottom:calc(68px + var(--bottom-nav-h,0px) + env(safe-area-inset-bottom,0px));display:flex;flex-direction:column;gap:8px;pointer-events:none}'
        + '.dl-row{pointer-events:auto;background:var(--surface-container-high,#242927);color:var(--on-surface,#dee4e0);border:1px solid var(--outline-variant,#3f4944);'
        + 'border-radius:16px;padding:12px 14px;box-shadow:0 10px 30px rgba(0,0,0,.45);font:14px/1.4 inherit;font-family:inherit}'
        + '.dl-row.flash{animation:dl-flash .6s ease 2}'
        + '.dl-top{display:flex;align-items:center;gap:10px}'
        + '.dl-name{flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-weight:600}'
        + '.dl-bar{height:6px;border-radius:3px;background:var(--surface-container-highest,#2f3432);overflow:hidden;margin:8px 0 6px}'
        + '.dl-bar i{display:block;height:100%;width:0;background:var(--primary,#5ddbb6);transition:width .2s}'
        + '.dl-bar.indeterminate i{width:35%;animation:dl-slide 1.2s linear infinite}'
        + '.dl-info{display:flex;align-items:center;gap:8px;color:var(--on-surface-variant,#bfc9c3);font-size:13px}'
        + '.dl-info span{flex:1;min-width:0}'
        + '.dl-row.error .dl-info span{color:var(--error,#ffb4ab)}'
        + '.dl-btn{border:0;border-radius:999px;padding:8px 14px;min-height:36px;font:600 13px/1 inherit;font-family:inherit;cursor:pointer;'
        + 'background:var(--surface-container-highest,#2f3432);color:var(--on-surface,#dee4e0)}'
        + '.dl-btn.primary{background:var(--primary,#5ddbb6);color:var(--on-primary,#00382a)}'
        + '.dl-x{border:0;background:none;color:inherit;opacity:.7;font-size:18px;line-height:1;padding:4px 6px;cursor:pointer}'
        + '.dl-busy{opacity:.55;pointer-events:none;animation:dl-pulse 1s ease-in-out infinite}'
        + '@keyframes dl-slide{from{transform:translateX(-100%)}to{transform:translateX(300%)}}'
        + '@keyframes dl-pulse{50%{opacity:.25}}'
        + '@keyframes dl-flash{50%{border-color:var(--primary,#5ddbb6)}}';

    function container() {
        var box = document.getElementById('dlPanel');
        if (box) return box;
        if (!document.getElementById('dlStyle')) {
            var st = document.createElement('style');
            st.id = 'dlStyle';
            st.textContent = STYLE;
            document.head.appendChild(st);
        }
        box = document.createElement('div');
        box.id = 'dlPanel';
        box.className = 'dl-panel';
        box.setAttribute('aria-live', 'polite');
        document.body.appendChild(box);
        return box;
    }

    function button(label, cls, onClick) {
        var b = document.createElement('button');
        b.type = 'button';
        b.className = 'dl-btn' + (cls ? ' ' + cls : '');
        b.textContent = label;
        b.addEventListener('click', onClick);
        return b;
    }

    /** One panel row per download; render() redraws its state. */
    function createRow(job) {
        var row = document.createElement('div');
        row.className = 'dl-row';
        row.innerHTML = '<div class="dl-top"><div class="dl-name"></div><button type="button" class="dl-x" aria-label="">×</button></div>'
            + '<div class="dl-bar indeterminate"><i></i></div><div class="dl-info"><span></span></div>';
        row.querySelector('.dl-name').textContent = job.filename;
        var x = row.querySelector('.dl-x');
        x.setAttribute('aria-label', t('close'));
        x.addEventListener('click', function () { job.state === 'running' ? job.controller.abort() : removeJob(job); });
        container().appendChild(row);
        job.row = row;
    }

    function render(job) {
        var row = job.row;
        if (!row) return;
        var bar = row.querySelector('.dl-bar');
        var fill = bar.querySelector('i');
        var info = row.querySelector('.dl-info');
        var text = info.querySelector('span');
        row.classList.toggle('error', job.state === 'error');
        info.querySelectorAll('.dl-btn').forEach(function (b) { b.remove(); });

        if (job.state === 'running') {
            bar.style.display = '';
            bar.classList.toggle('indeterminate', !job.total);
            if (job.total) {
                var pct = Math.min(100, Math.floor(job.received * 100 / job.total));
                fill.style.width = pct + '%';
                var pctText = lang() === 'tr' ? '%' + pct : pct + '%';
                text.textContent = t('downloading') + ' ' + pctText + ' · ' + mb(job.received) + ' / ' + mb(job.total);
            } else {
                text.textContent = t('downloading') + ' ' + mb(job.received);
            }
            info.appendChild(button(t('cancel'), '', function () { job.controller.abort(); }));
        } else if (job.state === 'done') {
            bar.style.display = 'none';
            text.textContent = job.message || t('done');
            (job.actions || []).forEach(function (a) { info.appendChild(button(a.label, a.primary ? 'primary' : '', a.run)); });
        } else if (job.state === 'error') {
            bar.style.display = 'none';
            text.textContent = t('failed') + (job.error ? ' (' + job.error + ')' : '');
            info.appendChild(button(t('retry'), 'primary', function () {
                removeJob(job);
                save(job.url, job.filename, { button: job.button });
            }));
        }
    }

    // ── jobs ──

    var jobs = {}; // url → running or finished job still on screen

    function setBusy(btn, busy) {
        if (!btn || !btn.classList) return;
        btn.classList.toggle('dl-busy', busy);
        if (busy) btn.setAttribute('aria-busy', 'true');
        else btn.removeAttribute('aria-busy');
    }

    function removeJob(job) {
        clearTimeout(job.hideTimer);
        if (job.row && job.row.parentNode) job.row.parentNode.removeChild(job.row);
        if (jobs[job.url] === job) delete jobs[job.url];
        if (job.objectUrl) setTimeout(function () { URL.revokeObjectURL(job.objectUrl); }, 60000);
    }

    function finishLater(job) {
        clearTimeout(job.hideTimer);
        job.hideTimer = setTimeout(function () { removeJob(job); }, DONE_HIDE_MS);
    }

    /** Hands a finished file to the browser's normal download (stays on the page: blob URL). */
    function browserDownload(job, href) {
        var a = document.createElement('a');
        a.href = href;
        a.download = job.filename;
        a.rel = 'noopener';
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
    }

    /** iPhone/iPad: Safari, or the app added to the home screen. */
    function isIOS() {
        return /iphone|ipad|ipod/i.test(navigator.userAgent)
            || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);
    }
    function isStandalone() {
        return navigator.standalone === true
            || !!(window.matchMedia && window.matchMedia('(display-mode: standalone)').matches);
    }

    /**
     * iOS has no download bar in a home-screen app and previews a downloaded file full screen with
     * no way back. There the finished file goes through the share sheet ("Save Video" to Photos,
     * "Save to Files"), which returns to the app. Safari only opens the share sheet from a tap, so
     * the panel offers a "Save" button instead of opening it when the download ends.
     */
    function shareFile(job, blob) {
        if (!isIOS() || !navigator.share || !navigator.canShare || typeof File !== 'function') return false;
        var file = new File([blob], job.filename, { type: blob.type || job.type });
        try {
            if (!navigator.canShare({ files: [file] })) return false;
        } catch (e) {
            return false;
        }
        job.state = 'done';
        job.message = t('ready');
        job.actions = [{
            label: t('save'), primary: true,
            run: function () {
                navigator.share({ files: [file], title: job.filename }).then(function () {
                    removeJob(job);
                    toast(t('saved'), 'success');
                }, function (err) {
                    if (err && err.name === 'AbortError') return; // closed the sheet: keep "Save" on screen
                    job.message = t('share_failed') + (err && err.message ? ' (' + err.message + ')' : '');
                    render(job);
                });
            },
        }];
        render(job);
        return true;
    }

    function deliver(job, blob) {
        if (shareFile(job, blob)) return;
        job.objectUrl = URL.createObjectURL(blob);
        browserDownload(job, job.objectUrl);
        job.state = 'done';
        render(job);
        finishLater(job);
    }

    /** MIME type for the saved file: the server's, else guessed from the name (iOS needs it for "Save Video"). */
    function typeFor(job, header) {
        var type = String(header || '').split(';')[0].trim();
        if (type && type !== 'application/octet-stream') return type;
        if (/\.mp4$/i.test(job.filename)) return 'video/mp4';
        if (/\.zip$/i.test(job.filename)) return 'application/zip';
        if (/\.json$/i.test(job.filename)) return 'application/json';
        return 'application/octet-stream';
    }

    function readBody(job, res) {
        if (!res.body || !res.body.getReader) return res.blob(); // no streams: no progress, still works
        var reader = res.body.getReader();
        var chunks = [];
        var lastPaint = 0;
        function pump() {
            return reader.read().then(function (step) {
                if (step.done) return new Blob(chunks, { type: job.type });
                chunks.push(step.value);
                job.received += step.value.length;
                var now = Date.now();
                if (now - lastPaint > 150) { lastPaint = now; render(job); }
                return pump();
            });
        }
        return pump();
    }

    function run(job) {
        return fetch(job.url, { signal: job.controller.signal, credentials: 'same-origin' }).then(function (res) {
            if (!res.ok) throw new Error('HTTP ' + res.status);
            job.total = Number(res.headers.get('content-length')) || 0;
            var named = /filename\*?=(?:UTF-8'')?"?([^";]+)"?/i.exec(res.headers.get('content-disposition') || '');
            if (job.autoName && named) {
                try { job.filename = decodeURIComponent(named[1]); } catch (e) { job.filename = named[1]; }
                if (job.row) job.row.querySelector('.dl-name').textContent = job.filename;
            }
            job.type = typeFor(job, res.headers.get('content-type'));
            if (job.total > MAX_IN_MEMORY) {
                // too big to hold in the page: let the browser fetch it itself. In an iOS home-screen
                // app a new window opens in an in-app Safari view whose "Done" returns to the app.
                job.controller.abort();
                if (isIOS() && isStandalone()) window.open(job.url, '_blank', 'noopener');
                else browserDownload(job, job.url);
                job.state = 'done';
                job.message = t('direct');
                render(job);
                finishLater(job);
                return;
            }
            render(job);
            return readBody(job, res).then(function (blob) { deliver(job, blob); });
        }).catch(function (err) {
            if (job.state === 'done') return;
            if (err && err.name === 'AbortError') {
                removeJob(job);
                toast(t('cancelled'), 'info');
                return;
            }
            job.state = 'error';
            job.error = err && err.message ? err.message : '';
            render(job);
        }).then(function () {
            setBusy(job.button, false);
        });
    }

    /** In-page downloads need fetch streams and cancellation. */
    function supported() {
        return typeof window.fetch === 'function' && typeof window.AbortController === 'function'
            && typeof window.ReadableStream === 'function' && typeof window.Blob === 'function';
    }

    /**
     * Downloads `url` as `filename` with in-page progress. A second tap on a file that is still
     * downloading only points at the running download.
     */
    function save(url, filename, opts) {
        opts = opts || {};
        if (!supported()) {
            // the head unit's Chrome 58 WebView has no AbortController: keep the plain download there
            browserDownload({ filename: filename || '' }, url);
            return Promise.resolve();
        }
        var running = jobs[url];
        if (running && running.state === 'running') {
            if (running.row) {
                running.row.classList.remove('flash');
                void running.row.offsetWidth; // restart the animation
                running.row.classList.add('flash');
            }
            toast(t('already'), 'info');
            return running.promise;
        }
        if (running) removeJob(running);
        var job = {
            url: url, filename: filename || url.split('/').pop().split('?')[0] || 'download', autoName: !filename,
            button: opts.button || null, controller: new AbortController(),
            state: 'running', received: 0, total: 0, type: 'application/octet-stream',
        };
        jobs[url] = job;
        createRow(job);
        render(job);
        setBusy(job.button, true);
        job.promise = run(job);
        return job.promise;
    }

    BYD.downloads = {
        save: save,
        isRunning: function (url) { return !!(jobs[url] && jobs[url].state === 'running'); },
    };
})();
