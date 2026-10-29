#!/usr/bin/env python3
"""
CloudStream 3 Site Compatibility Tester
Analyzes any streaming/anime site to determine if it can be turned into a working CloudStream 3 Kotlin plugin.
"""

import sys
import re
import urllib.parse
import urllib.request
import ssl
import json
from html.parser import HTMLParser


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
            with urllib.request.urlopen(req, timeout=15, context=ctx) as response:
                body = response.read().decode("utf-8", errors="ignore")
                headers_dict = dict(response.info())
                return response.status, body, headers_dict
        except urllib.error.HTTPError as e:
            body = e.read().decode("utf-8", errors="ignore") if e.fp else ""
            return e.code, body, dict(e.headers) if e.headers else {}
        except Exception as e:
            return 0, str(e), {}

    def analyze(self):
        print("="*60)
        print("  CloudStream 3 Site Compatibility Analyzer")
        print(f"  Target: {self.target_url}")
        print("="*60 + "\n")

        # 1. Test connection & Cloudflare detection
        print("[1/5] Testing HTTP Connection & Anti-Bot Protection...")
        status, html, headers = self._fetch(self.target_url)
        self.results["status_code"] = status

        if status == 0:
            print(f"  Connection failed: {html}")
            self.results["notes"].append(f"Connection failed: {html}")
            self._calculate_score()
            return self.results

        print(f"  HTTP Status Code: {status}")

        # Cloudflare / Turnstile detection
        server_header = headers.get("server", "").lower()
        cf_ray = headers.get("cf-ray", "")
        if "cloudflare" in server_header or cf_ray or "just a moment" in html.lower():
            self.results["cloudflare_detected"] = True
            print("  Cloudflare Protection Detected")

        if "turnstile" in html.lower() or "cf-turnstile" in html.lower() or "challenges.cloudflare.com" in html:
            self.results["turnstile_detected"] = True
            print("  Cloudflare Turnstile / Challenge Page Detected")

        if status in [403, 503] and self.results["cloudflare_detected"]:
            print("  Strict Cloudflare WAF Blocking detected (403/503).")
            self.results["notes"].append("Direct HTTP requests blocked by Cloudflare turnstile/challenge.")

        # 2. CMS Detection
        print("\n[2/5] Detecting CMS & Web Framework...")
        if "wp-content" in html or "wp-includes" in html or "WordPress" in html:
            self.results["cms_type"] = "WordPress"
            print("  CMS Identified: WordPress (Good compatibility with CloudStream NiceHttp & JSoup)")
        elif "laravel" in html.lower() or "XSRF-TOKEN" in str(headers):
            self.results["cms_type"] = "Laravel"
            print("  CMS Identified: Laravel Framework")
        elif "next" in html or "__NEXT_DATA__" in html:
            self.results["cms_type"] = "Next.js / React"
            print("  Framework Identified: Next.js (Uses JSON API / Hydration Data)")
        elif "nuxt" in html or "__NUXT__" in html:
            self.results["cms_type"] = "Nuxt.js / Vue"
            print("  Framework Identified: Nuxt.js (Uses JSON API)")
        else:
            print("  CMS Type: Generic Custom HTML / Web Platform")

        # 3. Search & Catalog Testing
        print("\n[3/5] Checking Search & Catalog Listings...")

        # Test common search patterns
        test_queries = ["one piece", "naruto", "attack", "a"]
        search_found = False

        # Pattern A: ?s= (WordPress standard)
        wp_search_url = f"{self.target_url}/?s={urllib.parse.quote(test_queries[0])}"
        s_code, s_html, _ = self._fetch(wp_search_url)
        if s_code == 200 and len(s_html) > 1000 and ("search" in s_html.lower() or "anime" in s_html.lower()):
            search_found = True
            self.results["search_capability"] = {
                "supported": True,
                "method": "GET",
                "url_pattern": f"{self.target_url}/?s=%s"
            }
            print(f"  Search Endpoint Found: GET ?s= query parameter")

        # Pattern B: /search?q= or /search/%s
        if not search_found:
            for s_path in [f"/search?q={test_queries[0]}", f"/search?key={test_queries[0]}",
                          f"/search/{test_queries[0]}", f"/search?s={test_queries[0]}"]:
                s_code, s_html, _ = self._fetch(self.target_url + s_path)
                if s_code == 200 and len(s_html) > 1000:
                    search_found = True
                    self.results["search_capability"] = {
                        "supported": True,
                        "method": "GET",
                        "url_pattern": self.target_url + s_path.replace(test_queries[0], "%s")
                    }
                    print(f"  Search Endpoint Found: GET {s_path}")
                    break

        # Check catalog/grid elements on home
        links = re.findall(r'href="([^"]+)"', html)
        anime_media_links = [l for l in links if any(kw in l.lower() for kw in ["anime", "watch", "series", "v", "e", "ep", "episode", "movie"])]
        if len(anime_media_links) > 5:
            self.results["catalog_detected"] = True
            print(f"  Catalog/Listings Detected: Found {len(anime_media_links)} potential media page links on homepage.")

        # 4. Episode & Video Embed Testing
        print("\n[4/5] Testing Media Pages & Video Extractor Hosts...")
        sample_media_url = None
        for l in anime_media_links:
            if l.startswith("http") and self.domain in l:
                sample_media_url = l
                break
            elif l.startswith("/"):
                sample_media_url = self.target_url + l
                break

        if sample_media_url:
            print(f"  Sample Media Page: {sample_media_url}")
            m_code, m_html, _ = self._fetch(sample_media_url)
            if m_code == 200:
                # Check for episodes
                ep_matches = re.findall(r'(episode|ep|الحلقة|سيرفرات-المشاهدة)[^"\'\>]*', m_html, re.IGNORECASE)
                if ep_matches or "episode" in m_html.lower() or "iframe" in m_html.lower():
                    self.results["episodes_detected"] = True
                    print("  Episode Listing / Player Structure Found")

                # Detect embeds / extractors
                known_hosts = [
                    "yonaplay", "soraplay", "dotplay", "vidhide", "streamtape", "filemoon",
                    "dood", "mp4upload", "ok.ru", "mega.nz", "google", "fembed", "vidoza",
                    "mixdrop", "streamsb", "voe", "upstream", "lulustream", "swatchseries"
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
                    print(f"  Detected Supported Extractor Hosts: {', '.join(found_extractors)}")
                else:
                    print("  Custom internal embed or JavaScript player used (May require custom ExtractorApi).")

        # 5. Calculate Score & Final Report
        self._calculate_score()
        self._print_report()
        return self.results

    def _calculate_score(self):
        score = 0

        # Base connectivity
        if self.results["status_code"] == 200:
            score += 25
        elif self.results["status_code"] in [301, 302, 307]:
            score += 20
        elif self.results["status_code"] == 403:
            score += 5

        # Cloudflare penalty if strictly blocking
        if self.results["cloudflare_detected"]:
            score -= 10
        if self.results["turnstile_detected"]:
            score -= 20

        # Features
        if self.results["catalog_detected"]:
            score += 20
        if self.results["search_capability"]["supported"]:
            score += 20
        if self.results["episodes_detected"]:
            score += 20
        if self.results["video_extractors_found"] or self.results["direct_m3u8_found"]:
            score += 25

        # Clamp 0 to 100
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

    def _print_report(self):
        print("\n" + "="*60)
        print("  ANALYSIS SUMMARY & RATING")
        print("="*60)
        print(f" Target Site         : {self.results['target']}")
        print(f" HTTP Status         : {self.results['status_code']}")
        print(f" CMS / Framework     : {self.results['cms_type']}")
        print(f" Cloudflare Active   : {'Yes' if self.results['cloudflare_detected'] else 'No'}")
        print(f" Turnstile Active    : {'Yes' if self.results['turnstile_detected'] else 'No'}")
        print(f" Search Support      : {'Supported (' + str(self.results['search_capability']['url_pattern']) + ')' if self.results['search_capability']['supported'] else 'Not auto-detected'}")
        print(f" Catalog Support     : {'Yes' if self.results['catalog_detected'] else 'No'}")
        print(f" Episodes Detected   : {'Yes' if self.results['episodes_detected'] else 'No'}")
        print(f" Video Extractor Hosts: {', '.join(self.results['video_extractors_found']) if self.results['video_extractors_found'] else 'Custom Player / Standard Extractor'}")
        print(f" Direct M3U8 Streams : {'Yes' if self.results['direct_m3u8_found'] else 'No'}")
        print("------------------------------------------------------------")
        print(f" FEASIBILITY SCORE   : {self.results['feasibility_score']} / 100")
        print(f" RATING              : {self.results['feasibility_rating']}")
        print("="*60 + "\n")

        print("CloudStream 3 Implementation Guidance:")
        if self.results['cms_type'] == "WordPress":
            print(" + Use JSoup HTML parsing for .anime-card, .ep-item")
        print(" + Add CloudflareKiller in plugin client initialization if cookies are required")
        print(" + Register custom ExtractorApi classes for any proprietary player endpoints")
        print("\nTool execution completed.\n")


def main():
    target = sys.argv[1] if len(sys.argv) > 1 else "https://witanime.site"
    tester = CloudStreamSiteTester(target)
    tester.analyze()

if __name__ == "__main__":
    main()