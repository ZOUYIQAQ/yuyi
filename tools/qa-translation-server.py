"""本地验收服务：从仓库根目录运行，仅验证流式传输和显示，固定回复不是翻译模型。"""
import http.server,json,ssl
class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    # 只返回受控的翻译夹具，验证系统链路而非云模型质量。
    def do_POST(self):
        body=self.rfile.read(int(self.headers['Content-Length']))
        with open('.qa/translation-requests.jsonl','ab') as f:f.write(body+b'\n')
        response=('data: '+json.dumps({'id':'qa-local','choices':[{'index':0,'delta':{'role':'assistant','content':'我们中学实行盒饭制，不能带饭时可以买五十日元的面包。'},'finish_reason':'stop'}],'usage':{'prompt_tokens':10,'completion_tokens':10,'total_tokens':20}},ensure_ascii=False)+'\n\ndata: [DONE]\n\n').encode()
        self.send_response(200)
        self.send_header('Content-Type','text/event-stream; charset=utf-8')
        self.send_header('Content-Length',str(len(response)))
        self.send_header('Connection','close')
        self.close_connection=True
        self.end_headers()
        self.wfile.write(response)
    # 记录测试地址和状态，不输出凭据。
    def log_message(self,format,*args):print('本地翻译验收：'+format%args,flush=True)
server=http.server.ThreadingHTTPServer(('127.0.0.1',18443),Handler)
context=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
context.load_cert_chain('.qa/tls/cert.pem','.qa/tls/key.pem')
server.socket=context.wrap_socket(server.socket,server_side=True)
print('本地 HTTPS 翻译验收服务已启动',flush=True)
server.serve_forever()
