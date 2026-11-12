"""给真前端探针铸一条登录态会话。

本机没有 psql，直连 PG：`user_sessions.token_hash` 是 bytea，就是 token 的 SHA-256 原始字节。
用法：
  python cast_session.py cast <token>     # 铸造（幂等：同 token 先删后插）
  python cast_session.py drop <token>     # 收尾清理
"""
import hashlib
import sys

import psycopg2

ENV_PATH = '/Users/z/workItem/CRMforLogistics-message-center-presplit-runtime/demo/message-center-spring/backend/.env'


def load_env(path):
    env = {}
    with open(path, encoding='utf-8') as handle:
        for line in handle:
            line = line.strip()
            if not line or line.startswith('#') or '=' not in line:
                continue
            key, value = line.split('=', 1)
            env[key.strip()] = value.strip().strip('"').strip("'")
    return env


def connect():
    env = load_env(ENV_PATH)
    return psycopg2.connect(
        host='localhost',
        port=5432,
        dbname='message_center',
        user='message_center',
        password=env.get('SPRING_DATASOURCE_PASSWORD', ''),
    )


def main():
    action, token = sys.argv[1], sys.argv[2]
    digest = hashlib.sha256(token.encode()).digest()
    with connect() as conn, conn.cursor() as cur:
        cur.execute('delete from user_sessions where token_hash = %s', (psycopg2.Binary(digest),))
        removed = cur.rowcount
        if action == 'drop':
            print(f'dropped={removed}')
            return
        cur.execute("select id, username from users where username = 'admin'")
        row = cur.fetchone()
        if not row:
            cur.execute('select id, username from users order by created_at limit 5')
            raise SystemExit('没有 admin 账号，候选：' + repr(cur.fetchall()))
        user_id, username = row
        cur.execute(
            """insert into user_sessions(user_id, token_hash, issued_at, expires_at, user_agent)
               values (%s, %s, now(), now() + interval '3 hours', 'mcp-visual-verify')""",
            (user_id, psycopg2.Binary(digest)),
        )
        print(f'cast ok user={username} id={user_id} replaced={removed}')


if __name__ == '__main__':
    main()
