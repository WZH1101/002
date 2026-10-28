package com.ctrip.controller;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 手机扫码确认页：GET /scan?scene=xxxx。
 *
 * <p>电脑显示的二维码内容就是这个地址（{@link com.ctrip.service.QrLoginService} 生成）。
 * 手机扫开后需要在这台设备上登录一次——只有已登录的设备才有权把登录机会授给另一台机器，
 * 这也是真实产品里「扫码登录」的语义：不是「谁扫到二维码谁就能登」，
 * 而是「已登录的那台替新设备点确认」。
 *
 * <p>页面由服务端直出，不引任何前端框架与外部资源：手机在局域网里打开它，
 * 依赖 CDN 就等于把功能挂在外网上。样式全部内联，脚本只用 fetch。
 *
 * <p>场景号先按 32 位十六进制校验再拼进页面，非法值只回一句提示、不回显原值，
 * 避免把用户可控内容塞进 HTML 与脚本里。
 */
@RestController
public class ScanController {

    @GetMapping(value = {"/scan", "/scan/"}, produces = "text/html;charset=UTF-8")
    public String scan(@RequestParam(value = "scene", required = false) String scene) {
        if (scene == null || !scene.matches("[0-9a-f]{32}")) {
            return ERROR_PAGE;
        }
        // 场景号已限定字符集，直接替换进模板没有转义风险
        return renderPage(scene);
    }

    private static final String ERROR_PAGE = """
            <!DOCTYPE html>
            <html lang="zh-CN"><head><meta charset="UTF-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <title>二维码无效</title></head>
            <body style="font-family:sans-serif;padding:32px;color:#333">
            <h3 style="margin:0 0 10px">二维码无效</h3>
            <p style="color:#888;margin:0">链接里的登录场景号不完整或格式不对，请回到电脑上重新生成二维码。</p>
            </body></html>
            """;

    /**
     * 手机确认页。占位符只有 __SCENE__（已通过字符集校验）。
     * 页面本身不含任何外部资源，脚本在文件末尾的 PAGE_JS 常量里拼接。
     */
    private static final String PAGE_HEAD = """
            <!DOCTYPE html>
            <html lang="zh-CN">
            <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <title>确认登录 · 悦途旅行</title>
            <style>
              body{font-family:-apple-system,'Microsoft YaHei',sans-serif;background:#f5f7fa;margin:0;padding:20px;color:#333}
              .card{max-width:420px;margin:0 auto;background:#fff;border-radius:12px;padding:22px;box-shadow:0 4px 16px rgba(0,0,0,.06)}
              h1{font-size:18px;margin:0 0 6px}
              .sub{color:#888;font-size:13px;margin-bottom:18px;line-height:1.6}
              .tabs{display:flex;gap:8px;margin-bottom:16px}
              .tab{flex:1;text-align:center;padding:8px 0;font-size:14px;border-radius:6px;background:#f2f4f8;color:#666;cursor:pointer}
              .tab.on{background:#2577e3;color:#fff}
              label{display:block;font-size:13px;color:#666;margin:12px 0 4px}
              input{width:100%;padding:11px 12px;border:1px solid #dcdfe6;border-radius:6px;font-size:15px;box-sizing:border-box}
              .row{display:flex;gap:8px}
              .row input{flex:1}
              button{width:100%;margin-top:16px;padding:12px 0;border:none;border-radius:6px;background:#2577e3;color:#fff;font-size:15px}
              button.ghost{background:#f2f4f8;color:#666;margin-top:10px}
              button.send{width:auto;padding:0 14px;margin-top:0;background:#eef3fb;color:#2577e3;font-size:13px;border-radius:6px}
              button:disabled{opacity:.55}
              .msg{margin-top:14px;font-size:13px;color:#e04c4c;line-height:1.6;word-break:break-all}
              .ok{color:#1fa971}
              .demo{margin-top:14px;background:#fff8ef;border:1px solid #ffe4c4;border-radius:6px;padding:10px 12px;font-size:13px;color:#d4700a;line-height:1.6}
              .who{background:#f7f9fc;border-radius:8px;padding:14px;margin:16px 0;font-size:14px;line-height:1.8}
              .who b{font-size:16px}
              .hide{display:none}
              .foot{max-width:420px;margin:14px auto 0;color:#aaa;font-size:12px;line-height:1.7}
            </style>
            </head>
            <body>
            <div class="card">
              <h1>确认登录</h1>
              <div class="sub">你在电脑上打开了「扫码登录」。<br>在这台手机上登录后点确认，电脑即可进入；不是你本人的请求就点取消。</div>

              <div id="login">
                <div class="tabs">
                  <div class="tab on" id="tabPwd" onclick="switchTab('pwd')">账号密码</div>
                  <div class="tab" id="tabSms" onclick="switchTab('sms')">手机验证码</div>
                </div>
                <div id="formPwd">
                  <label>用户名</label><input id="username" autocomplete="username" placeholder="请输入用户名">
                  <label>密码</label><input id="password" type="password" autocomplete="current-password" placeholder="请输入密码">
                  <button onclick="loginByPassword()">登录并继续</button>
                </div>
                <div id="formSms" class="hide">
                  <label>手机号</label>
                  <div class="row">
                    <input id="phone" type="tel" maxlength="11" placeholder="已注册的 11 位手机号">
                    <button class="send" id="sendBtn" onclick="sendCode()">获取验证码</button>
                  </div>
                  <label>验证码</label><input id="code" type="tel" maxlength="6" placeholder="6 位短信验证码">
                  <div id="demo" class="demo hide"></div>
                  <button onclick="loginByCode()">登录并继续</button>
                </div>
                <div class="msg" id="loginMsg"></div>
              </div>

              <div id="confirm" class="hide">
                <div class="who" id="who"></div>
                <button onclick="doConfirm()">确认登录</button>
                <button class="ghost" onclick="doReject()">取消</button>
                <div class="msg" id="confirmMsg"></div>
              </div>

              <div id="done" class="hide">
                <div class="msg ok" id="doneMsg"></div>
              </div>
            </div>
            <div class="foot">演示站：验证码为服务端随机生成，未接短信网关时会直接显示在本页。
            本次登录场景 2 分钟内有效，确认结果只能被电脑领取一次。</div>
            <script>
            __PAGE_JS__
            </script>
            </body>
            </html>
            """;

    /**
     * 确认页脚本。只用 fetch 与原生 DOM，不引任何外部库；
     * 昵称一律走 textContent 写入，不用 innerHTML——注册时昵称是不校验的自由文本。
     */
    private static final String PAGE_JS = """
            var SCENE = '__SCENE__';
            var token = sessionStorage.getItem('scanToken') || '';
            function $(id) { return document.getElementById(id); }
            function show(id) { ['login', 'confirm', 'done'].forEach(function (k) { $(k).className = k === id ? '' : 'hide'; }); }
            function api(path, method, body) {
                var opt = { method: method || 'GET', headers: { 'Content-Type': 'application/json' } };
                if (token) opt.headers['Authorization'] = 'Bearer ' + token;
                if (body) opt.body = JSON.stringify(body);
                return fetch(path, opt).then(function (r) { return r.json(); });
            }
            function setToken(t) {
                token = t || '';
                if (token) sessionStorage.setItem('scanToken', token); else sessionStorage.removeItem('scanToken');
            }
            function switchTab(which) {
                var pwd = which === 'pwd';
                $('tabPwd').className = 'tab' + (pwd ? ' on' : '');
                $('tabSms').className = 'tab' + (pwd ? '' : ' on');
                $('formPwd').className = pwd ? '' : 'hide';
                $('formSms').className = pwd ? 'hide' : '';
            }
            function afterLogin(user) {
                setToken(user.token);
                $('loginMsg').textContent = '';
                enterScene();
            }
            function loginByPassword() {
                var u = $('username').value.trim(), p = $('password').value;
                if (!u || !p) { $('loginMsg').textContent = '请输入用户名和密码'; return; }
                api('/api/auth/login', 'POST', { username: u, password: p }).then(function (r) {
                    if (r.code !== 0) { $('loginMsg').textContent = r.msg || '登录失败'; return; }
                    afterLogin(r.data);
                });
            }
            var left = 0;
            function countdown(sec) {
                left = sec;
                $('sendBtn').disabled = true;
                $('sendBtn').textContent = left + ' 秒后重发';
                var timer = setInterval(function () {
                    left--;
                    if (left <= 0) {
                        clearInterval(timer);
                        $('sendBtn').disabled = false;
                        $('sendBtn').textContent = '获取验证码';
                    } else {
                        $('sendBtn').textContent = left + ' 秒后重发';
                    }
                }, 1000);
            }
            function sendCode() {
                var p = $('phone').value.trim();
                if (!/^[0-9]{11}$/.test(p)) { $('loginMsg').textContent = '请输入 11 位手机号'; return; }
                api('/api/auth/sms/send', 'POST', { phone: p }).then(function (r) {
                    if (r.code !== 0) { $('loginMsg').textContent = r.msg || '发送失败'; return; }
                    var d = r.data || {};
                    if (d.demoCode) {
                        $('demo').className = 'demo';
                        $('demo').textContent = '演示验证码：' + d.demoCode
                            + '（' + Math.round((d.expireSeconds || 300) / 60) + ' 分钟内有效，只在本页显示，不发短信）';
                    }
                    countdown(d.resendSeconds || 60);
                });
            }
            function loginByCode() {
                var p = $('phone').value.trim(), c = $('code').value.trim();
                if (!/^[0-9]{11}$/.test(p)) { $('loginMsg').textContent = '请输入 11 位手机号'; return; }
                if (!/^[0-9]{6}$/.test(c)) { $('loginMsg').textContent = '请输入 6 位验证码'; return; }
                api('/api/auth/sms/login', 'POST', { phone: p, code: c }).then(function (r) {
                    if (r.code !== 0) { $('loginMsg').textContent = r.msg || '登录失败'; return; }
                    afterLogin(r.data);
                });
            }
            function enterScene() {
                show('confirm');
                $('confirmMsg').textContent = '正在登记扫码状态…';
                api('/api/auth/qrcode/' + SCENE + '/scan', 'POST', {}).then(function (r) {
                    if (r.code === 401) {
                        setToken('');
                        show('login');
                        $('loginMsg').textContent = r.msg || '登录态已失效，请重新登录';
                        return;
                    }
                    if (r.code !== 0) { $('confirmMsg').textContent = r.msg || '二维码状态已变更'; return; }
                    // 只读地取一次昵称与剩余时间。确认之后绝不再轮询：
                    // 状态变成 CONFIRMED 时，轮询响应里带的是给电脑新签发的会话令牌
                    api('/api/auth/qrcode/' + SCENE).then(function (s) {
                        var d = s.data || {};
                        var who = $('who');
                        who.textContent = '';
                        who.appendChild(document.createTextNode('将以 '));
                        var b = document.createElement('b');
                        b.textContent = d.nickname || '该账号';
                        who.appendChild(b);
                        who.appendChild(document.createTextNode(' 的身份登录这台电脑'));
                        var tip = document.createElement('div');
                        tip.style.cssText = 'color:#999;font-size:13px';
                        tip.textContent = '场景号 ' + SCENE + ' · 剩余 ' + (d.remainSeconds || 0) + ' 秒';
                        who.appendChild(tip);
                        $('confirmMsg').textContent = '';
                    });
                });
            }
            function doConfirm() {
                api('/api/auth/qrcode/' + SCENE + '/confirm', 'POST', {}).then(function (r) {
                    show('done');
                    $('doneMsg').textContent = r.code === 0 ? '已确认，请回到电脑继续操作。' : (r.msg || '确认失败');
                });
            }
            function doReject() {
                api('/api/auth/qrcode/' + SCENE + '/reject', 'POST', {}).then(function (r) {
                    show('done');
                    $('doneMsg').textContent = r.code === 0 ? '已取消本次登录。' : (r.msg || '取消失败');
                });
            }
            if (token) { enterScene(); } else { show('login'); }
            """;

    /** 组装：脚本进页面，再把已校验过的场景号填进去 */
    private static String renderPage(String scene) {
        return PAGE_HEAD.replace("__PAGE_JS__", PAGE_JS).replace("__SCENE__", scene);
    }
}
