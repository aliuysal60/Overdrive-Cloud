/**
 * BYD Champ - Client-side Auth Helper
 * 
 * Handles JWT session management and auth state.
 * Uses both cookie AND localStorage for redundancy (tunnels can drop cookies).
 * Include this in pages that need auth checking.
 */

const BYDAuth = {
    // Storage keys
    COOKIE_NAME: 'byd_session',
    STORAGE_KEY: 'byd_jwt',
    
    // Check if user is authenticated
    isAuthenticated: function() {
        return this.viaGateway() || !!this.getToken();
    },

    // OverDrive Cloud: the gateway marks its pages with a readable od_gw cookie and vouches for
    // the signed-in account on every request, so this car's own login does not apply there.
    viaGateway: function() {
        return typeof document !== 'undefined' && /(?:^|;\s*)od_gw=1(?:;|$)/.test(document.cookie);
    },

    // Where "sign in again" leads: the gateway's car list, or this car's login page
    loginUrl: function(returnTo) {
        if (this.viaGateway()) return '/gw/';
        return '/login.html' + (returnTo ? '?redirect=' + encodeURIComponent(returnTo) : '');
    },
    
    // Get JWT from cookie OR localStorage (fallback)
    getToken: function() {
        // Try cookie first
        const cookies = document.cookie.split(';');
        for (let cookie of cookies) {
            const [name, value] = cookie.trim().split('=');
            if (name === this.COOKIE_NAME && value) {
                return value;
            }
        }
        
        // Fallback to localStorage (tunnels sometimes drop cookies)
        try {
            const stored = localStorage.getItem(this.STORAGE_KEY);
            if (stored) {
                console.log('JWT loaded from localStorage (cookie was missing)');
                // Try to restore the cookie
                this._setCookie(stored);
                return stored;
            }
        } catch (e) {
            // localStorage might be blocked
        }
        
        return null;
    },
    
    // Set JWT in both cookie AND localStorage
    setToken: function(jwt, maxAgeDays = 365) {
        this._setCookie(jwt, maxAgeDays);
        
        // Also store in localStorage as backup
        try {
            localStorage.setItem(this.STORAGE_KEY, jwt);
        } catch (e) {
            console.warn('Could not save JWT to localStorage:', e);
        }
    },
    
    // Internal: set cookie only
    _setCookie: function(jwt, maxAgeDays = 365) {
        const maxAge = maxAgeDays * 24 * 60 * 60;
        const isSecure = window.location.protocol === 'https:';
        // Use Lax for HTTPS through tunnels, works better than Strict
        const sameSite = isSecure ? 'Lax; Secure' : 'Lax';
        document.cookie = `${this.COOKIE_NAME}=${jwt}; path=/; max-age=${maxAge}; SameSite=${sameSite}`;
    },
    
    // Clear JWT from both cookie AND localStorage
    clearToken: function() {
        document.cookie = `${this.COOKIE_NAME}=; path=/; max-age=0`;
        try {
            localStorage.removeItem(this.STORAGE_KEY);
        } catch (e) {
            // Ignore
        }
    },
    
    // Get auth headers for fetch requests
    // Always include Authorization header (more reliable than cookies through tunnels)
    getAuthHeaders: function() {
        const token = this.getToken();
        if (token) {
            return { 'Authorization': 'Bearer ' + token };
        }
        return {};
    },
    
    // Authenticated fetch wrapper
    // ALWAYS sends Authorization header (doesn't rely on cookies)
    fetch: async function(url, options = {}) {
        // Object.assign, not object spread: spread needs Chrome 60 and the head-unit floor is 58.
        const headers = Object.assign({}, options.headers, this.getAuthHeaders());
        
        const response = await fetch(url, Object.assign({}, options, { headers }));
        
        // Handle 401 - redirect to login
        if (response.status === 401) {
            window.location.href = this.loginUrl(window.location.pathname + window.location.search);
            throw new Error('Unauthorized');
        }
        
        return response;
    },
    
    // Check auth status from server
    checkStatus: async function() {
        try {
            const response = await fetch('/auth/status', {
                headers: this.getAuthHeaders()
            });
            return await response.json();
        } catch (e) {
            console.error('Auth status check failed:', e);
            return { deviceId: null };
        }
    },
    
    // Logout
    logout: async function() {
        try {
            await fetch('/auth/logout', { 
                method: 'POST',
                headers: this.getAuthHeaders()
            });
        } catch (e) {
            // Ignore errors
        }
        this.clearToken();
        window.location.href = this.loginUrl();
    },
    
    // Redirect to login if not authenticated
    requireAuth: function() {
        if (!this.isAuthenticated()) {
            window.location.href = this.loginUrl(window.location.pathname + window.location.search);
            return false;
        }
        return true;
    }
};

// SOTA: Override global fetch to automatically inject Authorization header.
// This ensures all API calls work through tunnels (where cookies get dropped due to SameSite policy).
// Only injects for same-origin or relative URL requests (not external APIs like ABRP/weather).
(function() {
    const originalFetch = window.fetch;
    window.fetch = function(url, options) {
        // Only inject auth for relative URLs or same-origin requests
        if (typeof url === 'string' && (url.startsWith('/') || url.startsWith(window.location.origin))) {
            const token = BYDAuth.getToken();
            if (token) {
                options = options || {};
                options.headers = options.headers || {};
                // Don't override if Authorization is already set
                if (!options.headers['Authorization'] && !options.headers['authorization']) {
                    if (options.headers instanceof Headers) {
                        if (!options.headers.has('Authorization')) {
                            options.headers.set('Authorization', 'Bearer ' + token);
                        }
                    } else {
                        options.headers['Authorization'] = 'Bearer ' + token;
                    }
                }
            }
        }
        return originalFetch.call(this, url, options);
    };
})();

// OverDrive Cloud: a way back to the car list on every page opened through the gateway
(function() {
    if (!BYDAuth.viaGateway()) return;
    function addButton() {
        if (document.getElementById('od-gw-back')) return;
        var tr = String(navigator.language || '').toLowerCase().indexOf('tr') === 0;
        var label = (window.BYD && BYD.i18n && BYD.i18n.t && BYD.i18n.t('cloud.my_cars')) || '';
        if (!label || label === 'cloud.my_cars') label = tr ? 'Araçlarım' : 'My cars';
        var a = document.createElement('a');
        a.id = 'od-gw-back';
        a.href = '/gw/';
        a.textContent = '← ' + label;
        a.setAttribute('style', 'position:fixed;left:12px;bottom:calc(12px + var(--bottom-nav-h, 0px) + env(safe-area-inset-bottom));'
            + 'z-index:2147483000;padding:8px 14px;border-radius:999px;background:rgba(15,20,18,.88);'
            + 'color:#4fd1a5;font:600 14px/1.2 system-ui,sans-serif;text-decoration:none;'
            + 'border:1px solid rgba(79,209,165,.35);box-shadow:0 2px 10px rgba(0,0,0,.35)');
        document.body.appendChild(a);
    }
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', addButton);
    else addButton();
})();

// Export for module systems
if (typeof module !== 'undefined' && module.exports) {
    module.exports = BYDAuth;
}
