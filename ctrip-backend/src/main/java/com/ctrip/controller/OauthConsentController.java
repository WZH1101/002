package com.ctrip.controller;

import com.ctrip.service.OAuthService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 第三方授权的<b>模拟</b>授权页：GET /oauth/authorize?state=xxxx。
 *
 * <p>真实项目里这一页不存在于本站——它是渠道方自己的域名下的页面（微信开放平台、QQ 互联、
 * 微博开放平台），用户在那边登录后渠道把浏览器重定向回本站的 callback，带着 code 与 state。
 * 演示环境没有渠道 appid/secret，也没有可对公网的 HTTPS 回调地址，所以由本站直出这张页面扮演渠道，
 * 让「第三方注册/登录」在本地能整条跑通。<b>页面上选中的身份并不代表真实的人</b>，
 * 它只是 {@link OAuthService} 内置的几个演示身份之一；接真渠道时删掉本类、
 * 改成一个 callback 处理器（用 code 换 access_token，再换 openid），其余状态机不变。
 *
 * <p>页面由服务端直出，不引任何外部资源与框架（与扫码确认页同一口径：本地演示不该依赖 CDN）。
 * 渲染进页面的只有两样东西：state（先按 32 位十六进制校验）与身份列表（来自服务端内置表，
 * 不是用户可控内容），因此直接拼串没有转义风险；昵称在脚本里仍走 textContent 写入。
 *
 * <p>这张页<em>绝不轮询</em>授权结果：状态一旦被它读到就等同把令牌领走，
 * 发起页那边只会看到「已被领取」。同意之后它只写状态、不再读。
 */
@RestController
public class OauthConsentController {

    private final OAuthService oauth;

    public OauthConsentController(OAuthService oauth) {
        this.oauth = oauth;
    }

    /** 渠道品牌色，只用于这张演示页的配色（与前端圆标、后端渠道注册表同一份，见 OAuthService.brandColor） */
    private static String brandColor(String provider) {
        return OAuthService.brandColor(provider);
    }

    @GetMapping(value = {"/oauth/authorize", "/oauth/authorize/"}, produces = "text/html;charset=UTF-8")
    public String authorize(@RequestParam(value = "state", required = false) String state) {
        if (state == null || !state.matches("[0-9a-f]{32}")) {
            return ERROR_PAGE;
        }
        Map<String, Object> info = oauth.peek(state);
        if (info == null) {
            return ERROR_PAGE;
        }
        return render(state, info);
    }

    private static final String ERROR_PAGE = """
            <!DOCTYPE html>
            <html lang="zh-CN"><head><meta charset="UTF-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <title>授权链接无效</title></head>
            <body style="font-family:sans-serif;padding:32px;color:#333">
            <h3 style="margin:0 0 10px">授权链接无效</h3>
            <p style="color:#888;margin:0">链接里的授权场景号不完整或已经过期，请回到发起页面重新进入。</p>
            </body></html>
            """;

    /** 已授权 / 已拒绝 / 已过期这三种情况下，页面只读展示结果，不给任何可操作按钮 */
    private static final String DONE_TEMPLATE = """
            <div class="state" id="stateBox">__STATE_MSG__</div>
            <div class="foot">本页是演示站内置的模拟授权页，不对应任何真实渠道账号。<br>
            授权结果只能被发起页面领取一次，且场景过期后自动作废。</div>
            """;

    @SuppressWarnings("unchecked")
    private String render(String state, Map<String, Object> info) {
        String provider = (String) info.get("provider");
        String providerName = (String) info.get("providerName");
        String status = (String) info.get("status");
        long remain = info.get("remainSeconds") instanceof Number n ? Math.max(0, n.longValue()) : 0;

        String head = PAGE_HEAD.replace("__PROVIDER_NAME__", providerName)
                .replace("__COLOR__", brandColor(provider));

        if (!OAuthService.PENDING.equals(status) || remain <= 0) {
            // 只读展示结果：不给按钮，也不注入脚本——脚本里的倒计时会去够不存在的元素
            String msg = switch (status) {
                case OAuthService.APPROVED -> "这个授权已经被发起页领取过了，无需重复操作。";
                case OAuthService.REJECTED -> "你已经拒绝过本次授权。回到发起页可以看到结果。";
                case OAuthService.EXPIRED -> "本次授权已过期，请回到发起页重新进入。";
                default -> "本次授权已失效，请回到发起页重新进入。";
            };
            return head + DONE_TEMPLATE.replace("__STATE_MSG__", msg) + TAIL.replace("__PAGE_JS__", "");
        }

        StringBuilder ids = new StringBuilder();
        List<Map<String, Object>> identities = (List<Map<String, Object>>) info.get("identities");
        if (identities != null) {
            for (Map<String, Object> one : identities) {
                String id = String.valueOf(one.get("id"));
                String nickname = String.valueOf(one.get("nickname"));
                String initial = nickname.isEmpty() ? "用" : nickname.substring(0, 1);
                ids.append("<label class=\"id\"><input type=\"radio\" name=\"identity\" value=\"").append(id)
                        .append("\"><span class=\"av\" style=\"background:").append(brandColor(provider)).append("\">")
                        .append(initial).append("</span><span class=\"nm\">").append(nickname)
                        .append("</span></label>");
            }
        }
        // replace(CharSequence,CharSequence) 是字面替换，不解释 $1 与 \\，
        // 所以把含这些字符的昵称写进页面也不会炸（那是 replaceAll 才有的坑）
        String js = PAGE_JS.replace("__STATE__", state).replace("__REMAIN__", String.valueOf(remain));
        return head + FORM_TEMPLATE.replace("__IDENTITIES__", ids.toString())
                + TAIL.replace("__PAGE_JS__", js);
    }

    private static final String FORM_TEMPLATE = """
            <div class="app">悦途旅行预订（演示）申请获取你的<b>公开信息</b>：昵称、头像。<br>
            授权后可以为你创建悦途账号；已有账号则直接登录，不会重复注册。</div>
            <div class="ids">
              <div class="ids-title">选择授权给本站的身份（模拟渠道提供的账号）</div>
              __IDENTITIES__
            </div>
            <button id="ok" onclick="approve()">同意并授权</button>
            <button class="ghost" id="no" onclick="reject()">拒绝</button>
            <div class="msg" id="msg"></div>
            <div class="foot">本页是演示站内置的模拟授权页：真实项目里这一页在渠道方域名下，
            渠道登录后会把浏览器重定向回本站 callback，由<b>服务端</b>用 code 换 openid。<br>
            <span id="left"></span></div>
            """;

    private static final String TAIL = """
            </div>
            <script>
            __PAGE_JS__
            </script>
            </body>
            </html>
            """;

    /** 脚本：只做同意/拒绝两次写操作，读完状态就不再问——轮询会把令牌领走，那是发起页的活儿 */
    private static final String PAGE_JS = """
            var STATE = '__STATE__';
            var LEFT = __REMAIN__;
            function $(id) { return document.getElementById(id); }
            function api(path, method, body) {
                var opt = { method: method, headers: { 'Content-Type': 'application/json' } };
                if (body) opt.body = JSON.stringify(body);
                return fetch(path, opt).then(function (r) { return r.json(); });
            }
            function done(text) {
                var box = document.createElement('div');
                box.className = 'state';
                box.textContent = text;
                document.querySelector('.card').innerHTML = '';
                document.querySelector('.card').appendChild(box);
            }
            function approve() {
                var pick = document.querySelector('input[name=identity]:checked');
                if (!pick) { $('msg').textContent = '请先选一个授权身份'; return; }
                $('ok').disabled = true; $('no').disabled = true;
                api('/api/auth/oauth/' + STATE + '/approve', 'POST', { identity: pick.value }).then(function (r) {
                    if (r.code !== 0) {
                        $('msg').textContent = r.msg || '授权失败';
                        $('ok').disabled = false; $('no').disabled = false;
                        return;
                    }
                    done((r.data && r.data.msg) || '已授权，请回到发起页继续');
                });
            }
            function reject() {
                $('ok').disabled = true; $('no').disabled = true;
                api('/api/auth/oauth/' + STATE + '/reject', 'POST', {}).then(function (r) {
                    if (r.code !== 0) { $('msg').textContent = r.msg || '操作失败'; return; }
                    done((r.data && r.data.msg) || '已拒绝本次授权');
                });
            }
            // 只做本地倒计时显示，不据此改服务端状态；到 0 就把按钮停掉，免得点了才知道过期
            if (LEFT > 0) {
                var timer = setInterval(function () {
                    LEFT--;
                    if (LEFT <= 0) {
                        clearInterval(timer);
                        $('left').textContent = '本次授权已超时，请回到发起页重新进入。';
                        $('ok').disabled = true; $('no').disabled = true;
                        return;
                    }
                    $('left').textContent = '本次授权 ' + LEFT + ' 秒内有效。';
                }, 1000);
                $('left').textContent = '本次授权 ' + LEFT + ' 秒内有效。';
            }
            """;

    private static final String PAGE_HEAD = """
            <!DOCTYPE html>
            <html lang="zh-CN">
            <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <title>__PROVIDER_NAME__ 授权 · 悦途旅行</title>
            <style>
              body{font-family:-apple-system,'Microsoft YaHei',sans-serif;background:#f5f7fa;margin:0;padding:20px;color:#333}
              .card{max-width:420px;margin:0 auto;background:#fff;border-radius:12px;padding:22px;box-shadow:0 4px 16px rgba(0,0,0,.06)}
              .brand{display:inline-block;color:#fff;background:__COLOR__;border-radius:6px;padding:4px 10px;font-size:14px;margin-bottom:12px}
              h1{font-size:18px;margin:0 0 6px}
              .sub{color:#888;font-size:13px;margin-bottom:16px;line-height:1.6}
              .app{background:#f7f9fc;border-radius:8px;padding:12px 14px;font-size:13px;line-height:1.8;color:#555;margin-bottom:16px}
              .ids{border:1px solid #eef0f4;border-radius:8px;overflow:hidden}
              .ids-title{font-size:12px;color:#999;padding:10px 14px;background:#fafbfd}
              .id{display:flex;align-items:center;gap:10px;padding:12px 14px;border-top:1px solid #f0f2f6;cursor:pointer;font-size:14px}
              .id:hover{background:#f8fbff}
              .av{width:30px;height:30px;border-radius:50%;color:#fff;display:inline-flex;align-items:center;justify-content:center;font-size:14px;flex:none}
              .nm{flex:1}
              button{width:100%;margin-top:16px;padding:12px 0;border:none;border-radius:6px;background:__COLOR__;color:#fff;font-size:15px}
              button.ghost{background:#f2f4f8;color:#666;margin-top:10px}
              button:disabled{opacity:.55}
              .msg{margin-top:12px;font-size:13px;color:#e04c4c;line-height:1.6;word-break:break-all}
              .state{background:#f2fbf6;border:1px solid #d7f0e0;color:#1fa971;border-radius:8px;padding:16px;font-size:15px;line-height:1.7}
              .foot{margin-top:16px;color:#aaa;font-size:12px;line-height:1.7}
            </style>
            </head>
            <body>
            <div class="card">
              <span class="brand">__PROVIDER_NAME__ 授权</span>
              <h1>授权登录「悦途旅行」</h1>
              <div class="sub">确认以下信息后，__PROVIDER_NAME__ 把你的公开昵称与头像提供给悦途旅行，
              用于创建或登录悦途账号。</div>
            """;
}
