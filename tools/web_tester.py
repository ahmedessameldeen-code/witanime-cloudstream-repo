#!/usr/bin/env python3
"""
CloudStream 3 Web Tester & Analyzer
Serves a modern single-page dashboard at http://localhost:8888 for testing site compatibility.
"""

import http.server
import socketserver
import json
import urllib.parse
import urllib.request
import ssl
import re
import sys

PORT = 8888

class CloudStreamSiteTester:
    USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    def __init__(self, target_url):
        if not target_url.startswith("http://") and not target_url.startswith("https://"):
            target_url = "https://" + target_url
        self.target_url = target_url.rstrip("/")
        self.parsed_url = urllib.parse.urlparse(self.target_url)
        self.domain = self.parsed_url.netloc

        self.results = {
            "target": self.target_url,
            "domain": self.domain,
            "status_code": 0,
            "cloudflare_detected": False,
            "turnstile_detected": False,
            "cms_type": "Unknown",
            "search_capability": {"supported": False, "method": None, "url_pattern": None},
            "catalog_detected": False,
            "episodes_detected": False,
            "video_extractors_found": [],
            "direct_m3u8_found": False,
            "feasibility_score": 0,
            "feasibility_rating": "UNKNOWN",
            "notes": [],
            "recommendations": []
        }

    def _fetch(self, url, headers=None, data=None):
        req_headers = {
            "User-Agent": self.USER_AGENT,
            "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
            "Accept-Language": "en-US,en;q=0.5",
            "Referer": self.target_url + "/"
        }
        if headers:
            req_headers.update(headers)

        ctx = ssl.create_default_context()
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE

        req = urllib.request.Request(url, data=data, headers=req_headers)
        try:
            with urllib.request.urlopen(req, timeout=12, context=ctx) as response:
                body = response.read().decode("utf-8", errors="ignore")
                headers_dict = dict(response.info())
                return response.status, body, headers_dict
        except urllib.error.HTTPError as e:
            body = e.read().decode("utf-8", errors="ignore") if e.fp else ""
            return e.code, body, dict(e.headers) if e.headers else {}
        except Exception as e:
            return 0, str(e), {}

    def analyze(self):
        status, html, headers = self._fetch(self.target_url)
        self.results["status_code"] = status

        if status == 0:
            self.results["notes"].append(f"Connection failed: {html}")
            self._calculate_score()
            return self.results

        server_header = headers.get("server", "").lower()
        cf_ray = headers.get("cf-ray", "")
        if "cloudflare" in server_header or cf_ray or "just a moment" in html.lower():
            self.results["cloudflare_detected"] = True

        if "turnstile" in html.lower() or "cf-turnstile" in html.lower() or "challenges.cloudflare.com" in html:
            self.results["turnstile_detected"] = True

        if status in [403, 503] and self.results["cloudflare_detected"]:
            self.results["notes"].append("Direct HTTP requests blocked by Cloudflare turnstile/challenge.")

        if "wp-content" in html or "wp-includes" in html or "WordPress" in html:
            self.results["cms_type"] = "WordPress"
        elif "laravel" in html.lower() or "XSRF-TOKEN" in str(headers):
            self.results["cms_type"] = "Laravel"
        elif "next" in html or "__NEXT_DATA__" in html:
            self.results["cms_type"] = "Next.js / React"
        elif "nuxt" in html or "__NUXT__" in html:
            self.results["cms_type"] = "Nuxt.js / Vue"
        else:
            self.results["cms_type"] = "Generic Custom HTML"

        test_query = "one piece"
        wp_search_url = f"{self.target_url}/?s={urllib.parse.quote(test_query)}"
        s_code, s_html, _ = self._fetch(wp_search_url)
        if s_code == 200 and len(s_html) > 1000 and ("search" in s_html.lower() or "anime" in s_html.lower()):
            self.results["search_capability"] = {
                "supported": True,
                "method": "GET",
                "url_pattern": f"{self.target_url}/?s=%s"
            }

        if not self.results["search_capability"]["supported"]:
            for s_path in [f"/search?q={test_query}", f"/search?key={test_query}", f"/search/{test_query}"]:
                s_code, s_html, _ = self._fetch(self.target_url + s_path)
                if s_code == 200 and len(s_html) > 1000:
                    self.results["search_capability"] = {
                        "supported": True,
                        "method": "GET",
                        "url_pattern": self.target_url + s_path.replace(test_query, "%s")
                    }
                    break

        links = re.findall(r'href="([^"]+)"', html)
        anime_media_links = [l for l in links if any(kw in l.lower() for kw in ["anime", "watch", "series", "v", "e", "ep", "episode", "movie"])]
        if len(anime_media_links) > 5:
            self.results["catalog_detected"] = True

        sample_media_url = None
        for l in anime_media_links:
            if l.startswith("http") and self.domain in l:
                sample_media_url = l
                break
            elif l.startswith("/"):
                sample_media_url = self.target_url + l
                break

        if sample_media_url:
            m_code, m_html, _ = self._fetch(sample_media_url)
            if m_code == 200:
                ep_matches = re.findall(r'(episode|ep|الحلقة|سيرفرات-المشاهدة)[^"\'\>]*', m_html, re.IGNORECASE)
                if ep_matches or "episode" in m_html.lower() or "iframe" in m_html.lower():
                    self.results["episodes_detected"] = True

                known_hosts = [
                    "yonaplay", "soraplay", "dotplay", "vidhide", "streamtape", "filemoon",
                    "dood", "mp4upload", "ok.ru", "mega.nz", "google", "fembed", "vidoza"
                ]

                iframes = re.findall(r'<iframe[^>]+src="([^"]+)"', m_html, re.IGNORECASE)
                embedded_links = iframes + re.findall(r'https?://[^\s"<>&]+', m_html)

                found_extractors = set()
                for el in embedded_links:
                    el_lower = el.lower()
                    if ".m3u8" in el_lower:
                        self.results["direct_m3u8_found"] = True
                    for host in known_hosts:
                        if host in el_lower:
                            found_extractors.add(host)

                if found_extractors:
                    self.results["video_extractors_found"] = list(found_extractors)

        self._calculate_score()
        return self.results

    def _calculate_score(self):
        score = 0
        if self.results["status_code"] == 200:
            score += 25
        elif self.results["status_code"] in [301, 302, 307]:
            score += 20
        elif self.results["status_code"] == 403:
            score += 5

        if self.results["cloudflare_detected"]:
            score -= 10
        if self.results["turnstile_detected"]:
            score -= 20

        if self.results["catalog_detected"]:
            score += 20
        if self.results["search_capability"]["supported"]:
            score += 20
        if self.results["episodes_detected"]:
            score += 20
        if self.results["video_extractors_found"] or self.results["direct_m3u8_found"]:
            score += 25

        score = max(0, min(100, score))
        self.results["feasibility_score"] = score

        if score >= 75:
            self.results["feasibility_rating"] = "HIGH FEASIBILITY (100% Recommended)"
            self.results["recommendations"].append("This site can easily be converted into a fully functional CloudStream 3 plugin.")
        elif score >= 50:
            self.results["feasibility_rating"] = "FEASIBLE WITH CUSTOM EXTRACTOR / BYPASS"
            self.results["recommendations"].append("Requires writing a custom ExtractorApi or using CloudflareKiller / cookie session handling in Kotlin.")
        else:
            self.results["feasibility_rating"] = "HIGH BLOCKING RISK"
            self.results["recommendations"].append("Site uses aggressive anti-bot protection or proprietary obfuscation.")


HTML_PAGE = """<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>CloudStream 3 — Site Compatibility Analyzer</title>
    <link href="https://fonts.googleapis.com/css2?family=Plus+Jakarta+Sans:wght@400;500;600;700;800&family=JetBrains+Mono:wght@400;600&display=swap" rel="stylesheet">
    <style>
        :root {
            --bg-dark: #090d16;
            --card-bg: rgba(18, 26, 43, 0.75);
            --card-border: rgba(255, 255, 255, 0.1);
            --accent-cyan: #00f2fe;
            --accent-blue: #4facfe;
            --accent-green: #00e676;
            --accent-yellow: #ffb300;
            --accent-red: #ff5252;
            --text-main: #f1f5f9;
            --text-muted: #94a3b8;
        }

        * { margin: 0; padding: 0; box-sizing: border-box; font-family: 'Plus Jakarta Sans', sans-serif; }

        body {
            background-color: var(--bg-dark);
            background-image:
                radial-gradient(at 0% 0%, rgba(0, 242, 254, 0.15) 0px, transparent 50%),
                radial-gradient(at 100% 100%, rgba(79, 172, 254, 0.15) 0px, transparent 50%);
            color: var(--text-main);
            min-height: 100vh;
            display: flex;
            flex-direction: column;
            align-items: center;
            padding: 2.5rem 1.5rem;
        }

        .container {
            width: 100%;
            max-width: 900px;
        }

        header {
            text-align: center;
            margin-bottom: 2.5rem;
        }

        .badge {
            display: inline-block;
            background: linear-gradient(135deg, var(--accent-cyan), var(--accent-blue));
            color: #000;
            font-weight: 800;
            font-size: 0.75rem;
            padding: 0.35rem 0.85rem;
            border-radius: 999px;
            text-transform: uppercase;
            letter-spacing: 1px;
            margin-bottom: 1rem;
            box-shadow: 0 0 20px rgba(0, 242, 254, 0.4);
        }

        h1 {
            font-size: 2.5rem;
            font-weight: 800;
            letter-spacing: -1px;
            background: linear-gradient(180deg, #ffffff 0%, #94a3b8 100%);
            -webkit-background-clip: text;
            -webkit-text-fill-color: transparent;
            margin-bottom: 0.5rem;
        }

        p.subtitle {
            color: var(--text-muted);
            font-size: 1rem;
        }

        .search-box {
            background: var(--card-bg);
            border: 1px solid var(--card-border);
            backdrop-filter: blur(20px);
            border-radius: 18px;
            padding: 0.6rem 0.6rem 0.6rem 1.2rem;
            display: flex;
            gap: 0.8rem;
            align-items: center;
            margin-bottom: 1rem;
            box-shadow: 0 10px 30px rgba(0, 0, 0, 0.5);
        }

        .search-box input {
            flex: 1;
            background: transparent;
            border: none;
            outline: none;
            color: #fff;
            font-size: 1.05rem;
        }

        .search-box button {
            background: linear-gradient(135deg, var(--accent-cyan), var(--accent-blue));
            border: none;
            color: #000;
            font-weight: 700;
            font-size: 0.95rem;
            padding: 0.85rem 1.8rem;
            border-radius: 12px;
            cursor: pointer;
            transition: all 0.2s ease;
            box-shadow: 0 4px 15px rgba(0, 242, 254, 0.3);
        }

        .search-box button:hover {
            transform: translateY(-2px);
            box-shadow: 0 6px 20px rgba(0, 242, 254, 0.5);
        }

        .presets {
            display: flex;
            gap: 0.6rem;
            flex-wrap: wrap;
            margin-bottom: 2rem;
            align-items: center;
        }

        .presets span {
            font-size: 0.85rem;
            color: var(--text-muted);
            margin-right: 0.3rem;
        }

        .chip {
            background: rgba(255, 255, 255, 0.05);
            border: 1px solid rgba(255, 255, 255, 0.1);
            color: #cbd5e1;
            padding: 0.4rem 0.85rem;
            border-radius: 999px;
            font-size: 0.82rem;
            cursor: pointer;
            transition: all 0.2s;
        }

        .chip:hover {
            background: rgba(0, 242, 254, 0.15);
            border-color: var(--accent-cyan);
            color: #fff;
        }

        .result-panel {
            display: none;
            animation: fadeIn 0.4s ease forwards;
        }

        @keyframes fadeIn {
            from { opacity: 0; transform: translateY(10px); }
            to { opacity: 1; transform: translateY(0); }
        }

        .score-card {
            background: var(--card-bg);
            border: 1px solid var(--card-border);
            border-radius: 20px;
            padding: 2rem;
            text-align: center;
            margin-bottom: 1.5rem;
            backdrop-filter: blur(20px);
            position: relative;
            overflow: hidden;
        }

        .score-number {
            font-size: 4.5rem;
            font-weight: 800;
            line-height: 1;
            margin-bottom: 0.5rem;
        }

        .score-green { color: var(--accent-green); text-shadow: 0 0 30px rgba(0, 230, 118, 0.4); }
        .score-yellow { color: var(--accent-yellow); text-shadow: 0 0 30px rgba(255, 179, 0, 0.4); }
        .score-red { color: var(--accent-red); text-shadow: 0 0 30px rgba(255, 82, 82, 0.4); }

        .score-label {
            font-size: 1.25rem;
            font-weight: 700;
            text-transform: uppercase;
            letter-spacing: 0.5px;
        }

        .grid {
            display: grid;
            grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
            gap: 1rem;
            margin-bottom: 1.5rem;
        }

        .card {
            background: var(--card-bg);
            border: 1px solid var(--card-border);
            border-radius: 16px;
            padding: 1.2rem 1.4rem;
            backdrop-filter: blur(20px);
        }

        .card-header {
            font-size: 0.8rem;
            text-transform: uppercase;
            color: var(--text-muted);
            font-weight: 700;
            letter-spacing: 0.5px;
            margin-bottom: 0.5rem;
        }

        .card-value {
            font-size: 1.15rem;
            font-weight: 700;
            color: #fff;
        }

        .code-block {
            background: rgba(0, 0, 0, 0.6);
            border: 1px solid rgba(255, 255, 255, 0.08);
            border-radius: 14px;
            padding: 1.2rem;
            font-family: 'JetBrains Mono', monospace;
            font-size: 0.85rem;
            color: #a5f3fc;
            white-space: pre-wrap;
            overflow-x: auto;
            margin-top: 1rem;
        }

        .loader {
            display: none;
            text-align: center;
            padding: 3rem 0;
        }

        .spinner {
            width: 50px;
            height: 50px;
            border: 4px solid rgba(255,255,255,0.1);
            border-left-color: var(--accent-cyan);
            border-radius: 50%;
            animation: spin 0.8s linear infinite;
            margin: 0 auto 1rem;
        }

        @keyframes spin { to { transform: rotate(360deg); } }
    </style>
</head>
<body>
    <div class="container">
        <header>
            <div class="badge">CloudStream 3 Dev Kit</div>
            <h1>Site Compatibility Analyzer</h1>
            <p class="subtitle">Test any anime / streaming website for instant CloudStream 3 plugin feasibility</p>
        </header>

        <div class="search-box">
            <input type="text" id="targetUrl" placeholder="Enter site URL (e.g. https://witanime.site)" value="https://witanime.site">
            <button onclick="runAnalysis()">Analyze Site</button>
        </div>

        <div class="presets">
            <span>Quick Test:</span>
            <div class="chip" onclick="setAndRun('https://witanime.site')">witanime.site</div>
            <div class="chip" onclick="setAndRun('https://yam.ahwaktv.net')">yam.ahwaktv.net</div>
            <div class="chip" onclick="setAndRun('https://m.myseed.tv')">m.myseed.tv</div>
        </div>

        <div class="loader" id="loader">
            <div class="spinner"></div>
            <p style="color: var(--text-muted);">Probing site structure, anti-bot protection & video extractors...</p>
        </div>

        <div class="result-panel" id="resultPanel">
            <div class="score-card">
                <div class="score-number" id="scoreValue">0</div>
                <div class="score-label" id="scoreRating">ANALYSIS COMPLETE</div>
                <p id="targetDomain" style="color: var(--text-muted); margin-top: 0.5rem; font-size: 0.9rem;"></p>
            </div>

            <div class="grid">
                <div class="card">
                    <div class="card-header">HTTP Status</div>
                    <div class="card-value" id="valStatus">-</div>
                </div>
                <div class="card">
                    <div class="card-header">CMS / Engine</div>
                    <div class="card-value" id="valCms">-</div>
                </div>
                <div class="card">
                    <div class="card-header">Cloudflare Protection</div>
                    <div class="card-value" id="valCf">-</div>
                </div>
                <div class="card">
                    <div class="card-header">Search Engine</div>
                    <div class="card-value" id="valSearch">-</div>
                </div>
                <div class="card">
                    <div class="card-header">Catalog Density</div>
                    <div class="card-value" id="valCatalog">-</div>
                </div>
                <div class="card">
                    <div class="card-header">Video Extractors</div>
                    <div class="card-value" id="valExtractors">-</div>
                </div>
            </div>

            <div class="card">
                <div class="card-header">CloudStream 3 Kotlin Integration Code</div>
                <div class="code-block" id="kotlinCode">// Scaffolding will generate here</div>
            </div>
        </div>
    </div>

    <script>
        function setAndRun(url) {
            document.getElementById('targetUrl').value = url;
            runAnalysis();
        }

        async function runAnalysis() {
            const url = document.getElementById('targetUrl').value.trim();
            if (!url) return;

            document.getElementById('resultPanel').style.display = 'none';
            document.getElementById('loader').style.display = 'block';

            try {
                const res = await fetch('/api/analyze', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ url: url })
                });

                const data = await res.json();
                renderResults(data);
            } catch (err) {
                alert('Analysis failed: ' + err.message);
            } finally {
                document.getElementById('loader').style.display = 'none';
            }
        }

        function renderResults(data) {
            const scoreEl = document.getElementById('scoreValue');
            scoreEl.innerText = data.feasibility_score + '/100';

            scoreEl.className = 'score-number ' + (
                data.feasibility_score >= 75 ? 'score-green' :
                data.feasibility_score >= 50 ? 'score-yellow' : 'score-red'
            );

            document.getElementById('scoreRating').innerText = data.feasibility_rating;
            document.getElementById('targetDomain').innerText = data.target;

            document.getElementById('valStatus').innerText = data.status_code + ' OK';
            document.getElementById('valCms').innerText = data.cms_type;
            document.getElementById('valCf').innerText = data.cloudflare_detected ? 'Active ⚠️' : 'None ✓';
            document.getElementById('valSearch').innerText = data.search_capability.supported ? 'GET ' + data.search_capability.url_pattern : 'Not detected';
            document.getElementById('valCatalog').innerText = data.catalog_detected ? 'Detected ✓' : 'Not found';
            document.getElementById('valExtractors').innerText = data.video_extractors_found.length ? data.video_extractors_found.join(', ') : 'Custom Player / JS Embed';

            const kotlinSnippet = `class ${data.domain.replace(/[^a-zA-Z0-9]/g, '')}Provider : MainAPI() {
    override var mainUrl = "${data.target}"
    override var name = "${data.domain}"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Anime)

    ${data.cloudflare_detected ? '// Enabled Cloudflare bypass\n    override val client = NiceHttp(CloudflareKiller())' : '// Standard NiceHttp client\n    override val client = NiceHttp()'}

    override async fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=\${query.encodeUri()}"
        val doc = client.get(url).document
        return doc.select(".anime-card").mapNotNull { it.toSearchResponse() }
    }
}`;
            document.getElementById('kotlinCode').innerText = kotlinSnippet;
            document.getElementById('resultPanel').style.display = 'block';
        }
    </script>
</body>
</html>
"""

class RequestHandler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.end_headers()
        self.wfile.write(HTML_PAGE.encode("utf-8"))

    def do_POST(self):
        if self.path == "/api/analyze":
            content_length = int(self.headers.get("Content-Length", 0))
            post_data = self.rfile.read(content_length)
            try:
                payload = json.loads(post_data.decode("utf-8"))
                target_url = payload.get("url", "https://witanime.site")

                tester = CloudStreamSiteTester(target_url)
                res = tester.analyze()

                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(json.dumps(res).encode("utf-8"))
            except Exception as e:
                self.send_response(500)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(json.dumps({"error": str(e)}).encode("utf-8"))
        else:
            self.send_response(404)
            self.end_headers()

def run_server():
    server_address = ('', PORT)
    httpd = socketserver.TCPServer(server_address, RequestHandler)
    print(f"CloudStream 3 Tester Dashboard listening on http://localhost:{PORT}")
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\nStopping server.")
        httpd.server_close()

if __name__ == "__main__":
    run_server()
