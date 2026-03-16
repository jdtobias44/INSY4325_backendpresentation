package com.marketplace;

import java.sql.*;

public class Database {

    private static Connection conn;

    public static Connection get() throws SQLException {
        if (conn == null || conn.isClosed()) {
            conn = DriverManager.getConnection("jdbc:sqlite:marketplace.db");
            conn.createStatement().execute("PRAGMA foreign_keys = ON");
            createTables();
        }
        return conn;
    }

    private static void createTables() throws SQLException {
        Statement st = conn.createStatement();

        st.execute("""
            CREATE TABLE IF NOT EXISTS users (
                user_id    INTEGER PRIMARY KEY AUTOINCREMENT,
                name       TEXT NOT NULL,
                email      TEXT NOT NULL UNIQUE,
                password   TEXT NOT NULL,
                role       TEXT NOT NULL DEFAULT 'CUSTOMER',
                address    TEXT,
                phone      TEXT,
                created_at TEXT DEFAULT (datetime('now'))
            )
        """);

        st.execute("""
            CREATE TABLE IF NOT EXISTS products (
                product_id       INTEGER PRIMARY KEY AUTOINCREMENT,
                seller_id        INTEGER NOT NULL,
                name             TEXT NOT NULL,
                description      TEXT,
                price            REAL NOT NULL,
                category         TEXT,
                condition_type   TEXT DEFAULT 'NEW',
                stock            INTEGER NOT NULL DEFAULT 0,
                image_url        TEXT,
                fulfillment_type TEXT DEFAULT 'BOTH',
                status           TEXT DEFAULT 'ACTIVE',
                created_at       TEXT DEFAULT (datetime('now')),
                FOREIGN KEY (seller_id) REFERENCES users(user_id)
            )
        """);

        st.execute("""
            CREATE TABLE IF NOT EXISTS cart (
                cart_id     INTEGER PRIMARY KEY AUTOINCREMENT,
                customer_id INTEGER NOT NULL UNIQUE,
                created_at  TEXT DEFAULT (datetime('now')),
                FOREIGN KEY (customer_id) REFERENCES users(user_id)
            )
        """);

        st.execute("""
            CREATE TABLE IF NOT EXISTS cart_items (
                cart_item_id INTEGER PRIMARY KEY AUTOINCREMENT,
                cart_id      INTEGER NOT NULL,
                product_id   INTEGER NOT NULL,
                quantity     INTEGER NOT NULL DEFAULT 1,
                FOREIGN KEY (cart_id)    REFERENCES cart(cart_id),
                FOREIGN KEY (product_id) REFERENCES products(product_id),
                UNIQUE(cart_id, product_id)
            )
        """);

        st.execute("""
            CREATE TABLE IF NOT EXISTS orders (
                order_id         INTEGER PRIMARY KEY AUTOINCREMENT,
                customer_id      INTEGER NOT NULL,
                total_price      REAL NOT NULL,
                status           TEXT DEFAULT 'PENDING',
                fulfillment_type TEXT NOT NULL,
                delivery_address TEXT,
                created_at       TEXT DEFAULT (datetime('now')),
                FOREIGN KEY (customer_id) REFERENCES users(user_id)
            )
        """);

        st.execute("""
            CREATE TABLE IF NOT EXISTS order_items (
                order_item_id     INTEGER PRIMARY KEY AUTOINCREMENT,
                order_id          INTEGER NOT NULL,
                product_id        INTEGER NOT NULL,
                quantity          INTEGER NOT NULL,
                price_at_purchase REAL NOT NULL,
                FOREIGN KEY (order_id)   REFERENCES orders(order_id),
                FOREIGN KEY (product_id) REFERENCES products(product_id)
            )
        """);

        st.execute("""
            CREATE TABLE IF NOT EXISTS payments (
                payment_id   INTEGER PRIMARY KEY AUTOINCREMENT,
                order_id     INTEGER NOT NULL UNIQUE,
                method       TEXT NOT NULL,
                status       TEXT DEFAULT 'PENDING',
                amount       REAL NOT NULL,
                processed_at TEXT DEFAULT (datetime('now')),
                FOREIGN KEY (order_id) REFERENCES orders(order_id)
            )
        """);

        // Insert sample data only if empty
        ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM users");
        if (rs.next() && rs.getInt(1) == 0) {
            st.execute("""
                INSERT INTO users (name, email, password, role, address) VALUES
                ('Alice Johnson', 'alice@email.com', 'password123', 'CUSTOMER', '123 Main St, Dallas TX'),
                ('Bob Smith',     'bob@email.com',   'password123', 'SELLER',   '456 Oak Ave, Fort Worth TX'),
                ('Carol White',   'carol@email.com', 'password123', 'SELLER',   '789 Pine Rd, Arlington TX')
            """);

            st.execute("""
                INSERT INTO products (seller_id, name, description, price, category, condition_type, stock, fulfillment_type) VALUES
                (2, 'Phone Case',    'Protective case for iPhone 14', 15.99, 'Electronics', 'NEW',  50, 'BOTH'),
                (2, 'Leather Belt',  'Genuine leather belt, size M',  29.99, 'Clothing',    'NEW',  20, 'DELIVERY'),
                (3, 'Coffee Table',  'Wooden coffee table',           75.00, 'Furniture',   'USED',  1, 'PICKUP'),
                (2, 'Wireless Mouse','Bluetooth mouse, barely used',  22.50, 'Electronics', 'USED', 15, 'BOTH'),
                (3, 'Desk Lamp',     'LED adjustable desk lamp',      18.00, 'Furniture',   'NEW',  30, 'BOTH')
            """);
        }
    }
}
