"""Public no-key MT route probe; only fixed synthetic fixtures, no user data."""
import html
import json
import sys
import urllib.parse
import urllib.request

text = sys.stdin.read()
source, target = sys.argv[1:3]
query = urllib.parse.urlencode({"client": "gtx", "sl": source, "tl": target, "dt": "t", "q": text})
urls = ["https://translate.googleapis.com/translate_a/single?" + query,
        "https://translate.google.com/translate_a/single?" + query]
errors = []
for url in urls:
    try:
        request = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0", "Accept": "application/json"})
        data = json.load(urllib.request.urlopen(request, timeout=10))
        result = "".join(part[0] for part in data[0] if part and isinstance(part[0], str))
        if not result.strip():
            raise ValueError("empty response")
        sys.stdout.write(html.unescape(result))
        break
    except Exception as error:
        errors.append(type(error).__name__ + ": " + str(error))
else:
    sys.stderr.write("; ".join(errors))
    sys.exit(1)
