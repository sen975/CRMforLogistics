# Spring 消息中心生产部署

本目录用于 `www.blindac.com` 的 Spring 版消息中心发布。GitHub Actions 只发布后端 Jar 和前端 `dist`；PostgreSQL、MinIO、FunASR 由服务器上的 Compose 独立运行，普通代码发布不会重启这些依赖。

## 一次性服务器准备

使用已配置的部署密钥登录服务器：

```bash
ssh -i ~/.ssh/github_actions_message_center -p 22 root@8.135.70.130
```

创建生产环境文件目录：

```bash
install -d -m 0700 /etc/message-center
install -d -m 0755 /opt/crm-logistics-message-center/incoming
```

服务器必须已安装并满足以下条件，发布脚本也会在切换版本前再次校验：

```bash
/usr/bin/java -version
docker compose version
curl --version
systemctl --version
```

Java 必须是 17。缺少 Java、Docker Compose、`curl`、`tar`、`realpath` 或 systemd 时，发布会在变更当前版本前失败。

从本机上传环境变量模板：

```bash
scp -i ~/.ssh/github_actions_message_center -P 22 \
  deploy/message-center.env.example \
  root@8.135.70.130:/etc/message-center/message-center.env
```

在服务器填写真实值，并收紧权限：

```bash
nano /etc/message-center/message-center.env
chmod 600 /etc/message-center/message-center.env
```

生产 `CREDENTIAL_MASTER_KEY` 可在服务器生成：

```bash
openssl rand -base64 32
```

不要启用 `dev` profile。首次部署固定使用 `chatapp-only`，确保未配置企业微信时仍可启动 ChatApp、邮件和电话转录链路。

Workflow 使用 Maven `production` profile 构建，生产 Jar 会排除 `application-dev.yml`，并在上传前检查该文件确实不在 Jar 内。仓库历史中已出现过的明文凭据仍应视为泄露：在阿里云、邮箱和企业微信后台轮换后，再更新服务器环境文件；排除 Jar 不能撤销历史泄露。

## 一次性启动 Compose 依赖

在本机把 Compose 和 FunASR 最小运行文件上传到服务器：

```bash
ssh -i ~/.ssh/github_actions_message_center -p 22 root@8.135.70.130 \
  'install -d -m 0755 /opt/crm-logistics-message-center/infra/secrets'

rsync -az \
  -e 'ssh -i ~/.ssh/github_actions_message_center -p 22' \
  demo/message-center-spring/backend/compose.yaml \
  demo/message-center-spring/backend/funasr-runtime \
  root@8.135.70.130:/opt/crm-logistics-message-center/infra/
```

在服务器创建 Compose 使用的三个 secret 文件，内容必须与 `message-center.env` 中的数据库和 MinIO 凭据一致：

```bash
chmod 700 /opt/crm-logistics-message-center/infra/secrets
nano /opt/crm-logistics-message-center/infra/secrets/postgres_password
nano /opt/crm-logistics-message-center/infra/secrets/minio_access_key
nano /opt/crm-logistics-message-center/infra/secrets/minio_secret_key
chmod 600 /opt/crm-logistics-message-center/infra/secrets/*
```

启动依赖：

```bash
cd /opt/crm-logistics-message-center/infra
docker compose up -d --build
docker compose ps
```

普通代码发布不会执行这条 Compose 命令。只有 `compose.yaml` 或 `funasr-runtime/` 发生变化时，才重新上传并手工执行 `docker compose up -d --build`。

## GitHub Actions Secrets

在仓库 `Settings -> Secrets and variables -> Actions` 配置：

- `PROD_HOST`: `8.135.70.130`
- `PROD_PORT`: `22`
- `PROD_USER`: `root`
- `PROD_SSH_KEY`: `~/.ssh/github_actions_message_center` 私钥全文
- `PROD_KNOWN_HOSTS`: 已验证指纹后的 `ssh-keyscan` 输出

推送到 `main` 后，Workflow 会测试、构建、上传并发布。服务未在两分钟内返回预期的未登录响应时，脚本会切回上一版本并输出最近 100 行 systemd 日志。

回滚只恢复 Jar、前端静态文件和 systemd unit，**不会回滚 Flyway 数据库迁移**。所有自动发布的数据库变更必须保持对上一版程序的兼容性；涉及删列、约束收紧、数据改写或不可逆迁移时，先做数据库备份并单独安排人工发布与恢复方案。

## 首次创建管理员

第一次 Workflow 发布成功后，在服务器停止常驻服务，并通过隐藏输入设置强密码：

```bash
systemctl stop message-center.service
cd /var/lib/message-center
set -a
. /etc/message-center/message-center.env
set +a
read -rsp '管理员密码: ' ADMIN_PASSWORD
echo
export ADMIN_USERNAME=admin ADMIN_PASSWORD
runuser --preserve-environment -u message-center -- \
  /usr/bin/java -jar \
  /opt/crm-logistics-message-center/current/backend/message-center.jar \
  bootstrap-admin
unset ADMIN_PASSWORD
systemctl start message-center.service
```

不得使用代码中的默认 `admin/admin`。`bootstrap-admin` 返回 `CREATED` 后再开放公网登录。

## Nginx 路由要求

前端根目录必须指向：

```nginx
root /opt/crm-logistics-message-center/current/frontend;
index index.html;

location / {
    try_files $uri $uri/ /index.html;
}
```

Spring API 全部转发到 8107。**两条 SSE 端点必须单独拿出来配**（`location =` 是精确匹配，
优先于 `^~ /api/`，与书写顺序无关）：只关缓冲不够，读超时也要放开 ——
默认的 `proxy_read_timeout` 是 60s，而这两条流都会出现几十秒「一个字节都不写」的间隔。

```nginx
location = /api/events {
    proxy_pass http://127.0.0.1:8107;
    proxy_http_version 1.1;
    proxy_set_header Connection "";
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto https;
    proxy_buffering off;
    proxy_cache off;
    proxy_read_timeout 1h;
}

# 助手对话（SSE）：一轮里服务端可能几十秒不写一个字节（只读工具执行期间），
# 而默认的 proxy_read_timeout 是 60s —— 会被 nginx 掐成 504，用户看到「重试」而原因不在前端。
# 缓冲也必须关掉（后端已带 X-Accel-Buffering: no，这里是双保险）：不关缓冲，
# 整条流会被攒到结束才吐出来，「逐字」当场失效，而后端日志一切正常。
location = /api/assistant/messages {
    proxy_pass http://127.0.0.1:8107;
    proxy_http_version 1.1;
    proxy_set_header Connection "";
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto https;
    proxy_buffering off;
    proxy_cache off;
    proxy_read_timeout 1h;
}

location ^~ /api/ {
    client_max_body_size 110m;
    proxy_pass http://127.0.0.1:8107;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto https;
}
```

旧程序的 `/hook_path` 可以继续单独代理到 `127.0.0.1:8067`，不要再把通用 `/api/` 转发到 8067。

## 日常发布

只提交本次修改涉及的文件：

```bash
git add <本次修改文件>
git commit -m "描述本次修改"
git push origin main
```

在 GitHub 仓库的 `Actions` 页面查看“部署 Spring 消息中心”执行记录。
