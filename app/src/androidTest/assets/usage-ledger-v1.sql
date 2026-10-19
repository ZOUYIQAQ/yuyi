-- 从修改前的v1账本源码原样保存，验证真实旧结构升级，不修改用户数据库。
CREATE TABLE sessions(id TEXT PRIMARY KEY,label TEXT NOT NULL,started_ms INTEGER NOT NULL,last_activity_ms INTEGER NOT NULL,ended_ms INTEGER);
CREATE TABLE streams(event_id TEXT PRIMARY KEY,session_id TEXT,category TEXT NOT NULL,platform_id TEXT NOT NULL,model TEXT NOT NULL,cumulative INTEGER NOT NULL,input_tokens INTEGER,output_tokens INTEGER,cached_tokens INTEGER,audio_tokens INTEGER,audio_seconds REAL);
CREATE TABLE usage_events(id INTEGER PRIMARY KEY AUTOINCREMENT,event_id TEXT NOT NULL,at_ms INTEGER NOT NULL,session_id TEXT,category TEXT NOT NULL,platform_id TEXT NOT NULL,model TEXT NOT NULL,input_tokens INTEGER,output_tokens INTEGER,cached_tokens INTEGER,audio_tokens INTEGER,audio_seconds REAL,rate_input TEXT,rate_output TEXT,rate_cache TEXT,rate_audio TEXT,rate_second TEXT,amount_yuan TEXT,unpriced INTEGER NOT NULL);
CREATE INDEX usage_events_at_ms ON usage_events(at_ms);
CREATE INDEX usage_events_session_at ON usage_events(session_id,at_ms);
CREATE TABLE manual_rates(platform_id TEXT NOT NULL,model TEXT NOT NULL,rate_input TEXT,rate_output TEXT,rate_cache TEXT,rate_audio TEXT,rate_second TEXT,PRIMARY KEY(platform_id,model));
CREATE INDEX IF NOT EXISTS usage_events_event_id_id ON usage_events(event_id,id);
