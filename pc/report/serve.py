"""支援 Range（影片才能拖曳跳轉）的靜態伺服器。用法：python serve.py 8131 <根目錄>"""
import http.server, os, re, sys
class H(http.server.SimpleHTTPRequestHandler):
    def send_head(self):
        r = self.headers.get("Range"); path = self.translate_path(self.path)
        if not r or not os.path.isfile(path): return super().send_head()
        m = re.match(r"bytes=(\d*)-(\d*)", r); size = os.path.getsize(path)
        a = int(m.group(1) or 0); b = int(m.group(2)) if m.group(2) else size - 1; b = min(b, size - 1)
        f = open(path, "rb"); f.seek(a)
        self.send_response(206); self.send_header("Content-Type", self.guess_type(path))
        self.send_header("Content-Range", f"bytes {a}-{b}/{size}"); self.send_header("Content-Length", str(b - a + 1))
        self.send_header("Accept-Ranges", "bytes"); self.end_headers(); self._left = b - a + 1; return f
    def copyfile(self, s, d):
        n = getattr(self, "_left", None)
        if n is None: return super().copyfile(s, d)
        d.write(s.read(n)); self._left = None
    def end_headers(self):
        self.send_header("Accept-Ranges", "bytes"); super().end_headers()
os.chdir(sys.argv[2]); http.server.ThreadingHTTPServer(("", int(sys.argv[1])), H).serve_forever()
