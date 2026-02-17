CREATE TABLE admin_users (
    user_id VARCHAR(255) PRIMARY KEY,
    display_name VARCHAR(255) NOT NULL,
    email VARCHAR(320),
    roles TEXT NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    reset_count INTEGER NOT NULL DEFAULT 0,
    reset_token VARCHAR(512),
    reset_requested_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_admin_users_active ON admin_users (active);
