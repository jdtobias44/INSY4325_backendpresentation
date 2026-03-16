package com.marketplace;

import com.google.gson.*;
import spark.*;
import java.sql.*;
import java.util.*;

import static spark.Spark.*;

public class Main {

    static Gson gson = new Gson();

    public static void main(String[] args) throws Exception {

        // Use PORT env variable for Render, default 8080 locally
        int port = System.getenv("PORT") != null
            ? Integer.parseInt(System.getenv("PORT")) : 8080;
        port(port);

        // Initialize database
        Database.get();

        // Allow cross-origin requests from frontend
        options("/*", (req, res) -> {
            res.header("Access-Control-Allow-Origin", "*");
            res.header("Access-Control-Allow-Methods", "GET,POST,PUT,DELETE,OPTIONS");
            res.header("Access-Control-Allow-Headers", "Content-Type");
            return "OK";
        });

        before((req, res) -> {
            res.header("Access-Control-Allow-Origin", "*");
            res.header("Access-Control-Allow-Methods", "GET,POST,PUT,DELETE,OPTIONS");
            res.header("Access-Control-Allow-Headers", "Content-Type");
            res.type("application/json");
        });

        // ── Health check ──────────────────────────────────────────────────
        get("/", (req, res) -> "{\"status\":\"Marketplace API is running\"}");

        // ═════════════════════════════════════════════════════════════════
        // AUTH
        // ═════════════════════════════════════════════════════════════════

        // POST /api/auth/register
        post("/api/auth/register", (req, res) -> {
            JsonObject body = JsonParser.parseString(req.body()).getAsJsonObject();
            String name     = body.get("name").getAsString();
            String email    = body.get("email").getAsString();
            String password = body.get("password").getAsString();
            String role     = body.has("role") ? body.get("role").getAsString() : "CUSTOMER";
            String address  = body.has("address") ? body.get("address").getAsString() : "";
            String phone    = body.has("phone") ? body.get("phone").getAsString() : "";

            Connection conn = Database.get();

            // Check if email exists
            PreparedStatement check = conn.prepareStatement("SELECT user_id FROM users WHERE email = ?");
            check.setString(1, email);
            if (check.executeQuery().next()) {
                res.status(409);
                return error("Email already registered.");
            }

            PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO users (name, email, password, role, address, phone) VALUES (?,?,?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, name);
            ps.setString(2, email);
            ps.setString(3, password);
            ps.setString(4, role.toUpperCase());
            ps.setString(5, address);
            ps.setString(6, phone);
            ps.executeUpdate();

            ResultSet keys = ps.getGeneratedKeys();
            keys.next();
            int userId = keys.getInt(1);

            res.status(201);
            return success("User registered successfully.", Map.of("userId", userId, "name", name, "role", role));
        });

        // POST /api/auth/login
        post("/api/auth/login", (req, res) -> {
            JsonObject body = JsonParser.parseString(req.body()).getAsJsonObject();
            String email    = body.get("email").getAsString();
            String password = body.get("password").getAsString();

            PreparedStatement ps = Database.get().prepareStatement(
                "SELECT * FROM users WHERE email = ? AND password = ?");
            ps.setString(1, email);
            ps.setString(2, password);
            ResultSet rs = ps.executeQuery();

            if (!rs.next()) {
                res.status(401);
                return error("Invalid email or password.");
            }

            return success("Login successful.", Map.of(
                "userId",  rs.getInt("user_id"),
                "name",    rs.getString("name"),
                "email",   rs.getString("email"),
                "role",    rs.getString("role"),
                "address", rs.getString("address") != null ? rs.getString("address") : ""
            ));
        });

        // GET /api/users/:id
        get("/api/users/:id", (req, res) -> {
            PreparedStatement ps = Database.get().prepareStatement(
                "SELECT user_id, name, email, role, address, phone, created_at FROM users WHERE user_id = ?");
            ps.setInt(1, Integer.parseInt(req.params("id")));
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) { res.status(404); return error("User not found."); }
            return gson.toJson(rowToMap(rs, "user_id","name","email","role","address","phone","created_at"));
        });

        // ═════════════════════════════════════════════════════════════════
        // PRODUCTS
        // ═════════════════════════════════════════════════════════════════

        // GET /api/products  or  GET /api/products?category=X
        get("/api/products", (req, res) -> {
            String category = req.queryParams("category");
            PreparedStatement ps;
            if (category != null && !category.isBlank()) {
                ps = Database.get().prepareStatement(
                    "SELECT * FROM products WHERE status = 'ACTIVE' AND category = ? ORDER BY created_at DESC");
                ps.setString(1, category);
            } else {
                ps = Database.get().prepareStatement(
                    "SELECT * FROM products WHERE status = 'ACTIVE' ORDER BY created_at DESC");
            }
            return gson.toJson(resultToList(ps.executeQuery()));
        });

        // GET /api/products/:id
        get("/api/products/:id", (req, res) -> {
            PreparedStatement ps = Database.get().prepareStatement("SELECT * FROM products WHERE product_id = ?");
            ps.setInt(1, Integer.parseInt(req.params("id")));
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) { res.status(404); return error("Product not found."); }
            return gson.toJson(rowToMap(rs, "product_id","seller_id","name","description",
                "price","category","condition_type","stock","image_url","fulfillment_type","status","created_at"));
        });

        // POST /api/products  (seller adds product)
        post("/api/products", (req, res) -> {
            JsonObject b = JsonParser.parseString(req.body()).getAsJsonObject();
            PreparedStatement ps = Database.get().prepareStatement(
                "INSERT INTO products (seller_id,name,description,price,category,condition_type,stock,image_url,fulfillment_type) VALUES (?,?,?,?,?,?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS);
            ps.setInt(1,    b.get("sellerId").getAsInt());
            ps.setString(2, b.get("name").getAsString());
            ps.setString(3, b.has("description") ? b.get("description").getAsString() : "");
            ps.setDouble(4, b.get("price").getAsDouble());
            ps.setString(5, b.has("category") ? b.get("category").getAsString() : "General");
            ps.setString(6, b.has("condition") ? b.get("condition").getAsString() : "NEW");
            ps.setInt(7,    b.get("stock").getAsInt());
            ps.setString(8, b.has("imageUrl") ? b.get("imageUrl").getAsString() : "");
            ps.setString(9, b.has("fulfillmentType") ? b.get("fulfillmentType").getAsString() : "BOTH");
            ps.executeUpdate();
            ResultSet keys = ps.getGeneratedKeys(); keys.next();
            res.status(201);
            return success("Product created.", Map.of("productId", keys.getInt(1)));
        });

        // PUT /api/products/:id
        put("/api/products/:id", (req, res) -> {
            int id = Integer.parseInt(req.params("id"));
            JsonObject b = JsonParser.parseString(req.body()).getAsJsonObject();
            PreparedStatement ps = Database.get().prepareStatement(
                "UPDATE products SET name=?, description=?, price=?, stock=?, status=?, image_url=? WHERE product_id=?");
            ps.setString(1, b.get("name").getAsString());
            ps.setString(2, b.has("description") ? b.get("description").getAsString() : "");
            ps.setDouble(3, b.get("price").getAsDouble());
            ps.setInt(4,    b.get("stock").getAsInt());
            ps.setString(5, b.has("status") ? b.get("status").getAsString() : "ACTIVE");
            ps.setString(6, b.has("imageUrl") ? b.get("imageUrl").getAsString() : "");
            ps.setInt(7, id);
            ps.executeUpdate();
            return success("Product updated.", Map.of("productId", id));
        });

        // DELETE /api/products/:id
        delete("/api/products/:id", (req, res) -> {
            PreparedStatement ps = Database.get().prepareStatement("DELETE FROM products WHERE product_id = ?");
            ps.setInt(1, Integer.parseInt(req.params("id")));
            ps.executeUpdate();
            return success("Product deleted.", Map.of());
        });

        // ═════════════════════════════════════════════════════════════════
        // CART
        // ═════════════════════════════════════════════════════════════════

        // GET /api/cart?customerId=X
        get("/api/cart", (req, res) -> {
            int customerId = Integer.parseInt(req.queryParams("customerId"));
            int cartId = getOrCreateCart(customerId);
            PreparedStatement ps = Database.get().prepareStatement(
                "SELECT ci.*, p.name, p.price, p.image_url FROM cart_items ci " +
                "JOIN products p ON ci.product_id = p.product_id WHERE ci.cart_id = ?");
            ps.setInt(1, cartId);
            List<Map<String,Object>> items = resultToList(ps.executeQuery());
            return gson.toJson(Map.of("cartId", cartId, "customerId", customerId, "items", items));
        });

        // POST /api/cart/items
        post("/api/cart/items", (req, res) -> {
            JsonObject b = JsonParser.parseString(req.body()).getAsJsonObject();
            int customerId = b.get("customerId").getAsInt();
            int productId  = b.get("productId").getAsInt();
            int quantity   = b.get("quantity").getAsInt();
            int cartId     = getOrCreateCart(customerId);

            // Check stock
            PreparedStatement stock = Database.get().prepareStatement("SELECT stock FROM products WHERE product_id = ?");
            stock.setInt(1, productId);
            ResultSet sr = stock.executeQuery();
            if (!sr.next() || sr.getInt("stock") < quantity) {
                res.status(400); return error("Not enough stock available.");
            }

            PreparedStatement ps = Database.get().prepareStatement(
                "INSERT INTO cart_items (cart_id, product_id, quantity) VALUES (?,?,?) " +
                "ON CONFLICT(cart_id, product_id) DO UPDATE SET quantity = quantity + ?");
            ps.setInt(1, cartId); ps.setInt(2, productId);
            ps.setInt(3, quantity); ps.setInt(4, quantity);
            ps.executeUpdate();
            res.status(201);
            return success("Item added to cart.", Map.of("cartId", cartId));
        });

        // PUT /api/cart/items/:productId
        put("/api/cart/items/:productId", (req, res) -> {
            JsonObject b = JsonParser.parseString(req.body()).getAsJsonObject();
            int customerId = b.get("customerId").getAsInt();
            int productId  = Integer.parseInt(req.params("productId"));
            int quantity   = b.get("quantity").getAsInt();
            int cartId     = getOrCreateCart(customerId);
            PreparedStatement ps = Database.get().prepareStatement(
                "UPDATE cart_items SET quantity = ? WHERE cart_id = ? AND product_id = ?");
            ps.setInt(1, quantity); ps.setInt(2, cartId); ps.setInt(3, productId);
            ps.executeUpdate();
            return success("Cart updated.", Map.of());
        });

        // DELETE /api/cart/items/:productId?customerId=X
        delete("/api/cart/items/:productId", (req, res) -> {
            int customerId = Integer.parseInt(req.queryParams("customerId"));
            int productId  = Integer.parseInt(req.params("productId"));
            int cartId     = getOrCreateCart(customerId);
            PreparedStatement ps = Database.get().prepareStatement(
                "DELETE FROM cart_items WHERE cart_id = ? AND product_id = ?");
            ps.setInt(1, cartId); ps.setInt(2, productId);
            ps.executeUpdate();
            return success("Item removed.", Map.of());
        });

        // ═════════════════════════════════════════════════════════════════
        // ORDERS
        // ═════════════════════════════════════════════════════════════════

        // POST /api/orders  (checkout)
        post("/api/orders", (req, res) -> {
            JsonObject b = JsonParser.parseString(req.body()).getAsJsonObject();
            int customerId       = b.get("customerId").getAsInt();
            String fulfillment   = b.has("fulfillmentType") ? b.get("fulfillmentType").getAsString() : "DELIVERY";
            String deliveryAddr  = b.has("deliveryAddress") ? b.get("deliveryAddress").getAsString() : "";
            int cartId           = getOrCreateCart(customerId);
            Connection conn      = Database.get();

            // Get cart items
            PreparedStatement cartPs = conn.prepareStatement(
                "SELECT ci.product_id, ci.quantity, p.price, p.stock, p.name FROM cart_items ci " +
                "JOIN products p ON ci.product_id = p.product_id WHERE ci.cart_id = ?");
            cartPs.setInt(1, cartId);
            List<Map<String,Object>> items = resultToList(cartPs.executeQuery());

            if (items.isEmpty()) { res.status(400); return error("Cart is empty."); }

            // Calculate total
            double total = 0;
            for (Map<String,Object> item : items) {
                double price = ((Number) item.get("price")).doubleValue();
                int qty      = ((Number) item.get("quantity")).intValue();
                total       += price * qty;
            }

            // Create order
            PreparedStatement orderPs = conn.prepareStatement(
                "INSERT INTO orders (customer_id, total_price, fulfillment_type, delivery_address) VALUES (?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS);
            orderPs.setInt(1, customerId);
            orderPs.setDouble(2, total);
            orderPs.setString(3, fulfillment);
            orderPs.setString(4, deliveryAddr);
            orderPs.executeUpdate();
            ResultSet keys = orderPs.getGeneratedKeys(); keys.next();
            int orderId = keys.getInt(1);

            // Create order items & decrement stock
            for (Map<String,Object> item : items) {
                int productId = ((Number) item.get("product_id")).intValue();
                int qty       = ((Number) item.get("quantity")).intValue();
                double price  = ((Number) item.get("price")).doubleValue();
                int stock     = ((Number) item.get("stock")).intValue();

                PreparedStatement oi = conn.prepareStatement(
                    "INSERT INTO order_items (order_id, product_id, quantity, price_at_purchase) VALUES (?,?,?,?)");
                oi.setInt(1, orderId); oi.setInt(2, productId);
                oi.setInt(3, qty); oi.setDouble(4, price);
                oi.executeUpdate();

                PreparedStatement upStock = conn.prepareStatement(
                    "UPDATE products SET stock = ?, status = ? WHERE product_id = ?");
                int newStock = stock - qty;
                upStock.setInt(1, newStock);
                upStock.setString(2, newStock <= 0 ? "SOLD_OUT" : "ACTIVE");
                upStock.setInt(3, productId);
                upStock.executeUpdate();
            }

            // Clear cart
            PreparedStatement clear = conn.prepareStatement("DELETE FROM cart_items WHERE cart_id = ?");
            clear.setInt(1, cartId);
            clear.executeUpdate();

            res.status(201);
            return success("Order placed successfully.", Map.of("orderId", orderId, "total", total));
        });

        // GET /api/orders?customerId=X
        get("/api/orders", (req, res) -> {
            String customerIdParam = req.queryParams("customerId");
            if (customerIdParam == null) { res.status(400); return error("customerId required."); }
            PreparedStatement ps = Database.get().prepareStatement(
                "SELECT * FROM orders WHERE customer_id = ? ORDER BY created_at DESC");
            ps.setInt(1, Integer.parseInt(customerIdParam));
            return gson.toJson(resultToList(ps.executeQuery()));
        });

        // GET /api/orders/:id
        get("/api/orders/:id", (req, res) -> {
            int orderId = Integer.parseInt(req.params("id"));
            PreparedStatement ps = Database.get().prepareStatement("SELECT * FROM orders WHERE order_id = ?");
            ps.setInt(1, orderId);
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) { res.status(404); return error("Order not found."); }
            Map<String,Object> order = rowToMap(rs, "order_id","customer_id","total_price",
                "status","fulfillment_type","delivery_address","created_at");

            PreparedStatement items = Database.get().prepareStatement(
                "SELECT oi.*, p.name FROM order_items oi JOIN products p ON oi.product_id = p.product_id WHERE oi.order_id = ?");
            items.setInt(1, orderId);
            order.put("items", resultToList(items.executeQuery()));
            return gson.toJson(order);
        });

        // PUT /api/orders/:id/status
        put("/api/orders/:id/status", (req, res) -> {
            JsonObject b = JsonParser.parseString(req.body()).getAsJsonObject();
            PreparedStatement ps = Database.get().prepareStatement("UPDATE orders SET status = ? WHERE order_id = ?");
            ps.setString(1, b.get("status").getAsString());
            ps.setInt(2, Integer.parseInt(req.params("id")));
            ps.executeUpdate();
            return success("Order status updated.", Map.of());
        });

        // ═════════════════════════════════════════════════════════════════
        // SELLER DASHBOARD
        // ═════════════════════════════════════════════════════════════════

        // GET /api/seller/:sellerId/products
        get("/api/seller/:sellerId/products", (req, res) -> {
            PreparedStatement ps = Database.get().prepareStatement(
                "SELECT * FROM products WHERE seller_id = ? ORDER BY created_at DESC");
            ps.setInt(1, Integer.parseInt(req.params("sellerId")));
            return gson.toJson(resultToList(ps.executeQuery()));
        });

        // GET /api/seller/:sellerId/orders
        get("/api/seller/:sellerId/orders", (req, res) -> {
            PreparedStatement ps = Database.get().prepareStatement(
                "SELECT DISTINCT o.* FROM orders o " +
                "JOIN order_items oi ON o.order_id = oi.order_id " +
                "JOIN products p ON oi.product_id = p.product_id " +
                "WHERE p.seller_id = ? ORDER BY o.created_at DESC");
            ps.setInt(1, Integer.parseInt(req.params("sellerId")));
            return gson.toJson(resultToList(ps.executeQuery()));
        });

        // ═════════════════════════════════════════════════════════════════
        // PAYMENTS
        // ═════════════════════════════════════════════════════════════════

        // POST /api/payments
        post("/api/payments", (req, res) -> {
            JsonObject b  = JsonParser.parseString(req.body()).getAsJsonObject();
            int orderId   = b.get("orderId").getAsInt();
            String method = b.get("method").getAsString();
            double amount = b.get("amount").getAsDouble();

            // Validate order exists
            PreparedStatement check = Database.get().prepareStatement("SELECT total_price FROM orders WHERE order_id = ?");
            check.setInt(1, orderId);
            ResultSet rs = check.executeQuery();
            if (!rs.next()) { res.status(404); return error("Order not found."); }

            PreparedStatement ps = Database.get().prepareStatement(
                "INSERT INTO payments (order_id, method, status, amount) VALUES (?,?,'COMPLETED',?)",
                Statement.RETURN_GENERATED_KEYS);
            ps.setInt(1, orderId);
            ps.setString(2, method.toUpperCase());
            ps.setDouble(3, amount);
            ps.executeUpdate();

            // Update order to CONFIRMED
            PreparedStatement upOrder = Database.get().prepareStatement("UPDATE orders SET status = 'CONFIRMED' WHERE order_id = ?");
            upOrder.setInt(1, orderId);
            upOrder.executeUpdate();

            ResultSet keys = ps.getGeneratedKeys(); keys.next();
            res.status(201);
            return success("Payment processed successfully.", Map.of("paymentId", keys.getInt(1), "status", "COMPLETED"));
        });

        // GET /api/payments/:orderId
        get("/api/payments/:orderId", (req, res) -> {
            PreparedStatement ps = Database.get().prepareStatement("SELECT * FROM payments WHERE order_id = ?");
            ps.setInt(1, Integer.parseInt(req.params("orderId")));
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) { res.status(404); return error("No payment found for this order."); }
            return gson.toJson(rowToMap(rs, "payment_id","order_id","method","status","amount","processed_at"));
        });

        // POST /api/payments/:orderId/refund
        post("/api/payments/:orderId/refund", (req, res) -> {
            int orderId = Integer.parseInt(req.params("orderId"));
            PreparedStatement ps = Database.get().prepareStatement(
                "UPDATE payments SET status = 'REFUNDED' WHERE order_id = ?");
            ps.setInt(1, orderId);
            ps.executeUpdate();
            PreparedStatement upOrder = Database.get().prepareStatement("UPDATE orders SET status = 'CANCELLED' WHERE order_id = ?");
            upOrder.setInt(1, orderId);
            upOrder.executeUpdate();
            return success("Payment refunded.", Map.of());
        });

        System.out.println("✅ Marketplace API running on port " + port);
    }

    // ─── Helpers ────────────────────────────────────────────────────────────

    static int getOrCreateCart(int customerId) throws SQLException {
        PreparedStatement find = Database.get().prepareStatement("SELECT cart_id FROM cart WHERE customer_id = ?");
        find.setInt(1, customerId);
        ResultSet rs = find.executeQuery();
        if (rs.next()) return rs.getInt("cart_id");

        PreparedStatement create = Database.get().prepareStatement(
            "INSERT INTO cart (customer_id) VALUES (?)", Statement.RETURN_GENERATED_KEYS);
        create.setInt(1, customerId);
        create.executeUpdate();
        ResultSet keys = create.getGeneratedKeys();
        keys.next();
        return keys.getInt(1);
    }

    static List<Map<String,Object>> resultToList(ResultSet rs) throws SQLException {
        List<Map<String,Object>> list = new ArrayList<>();
        ResultSetMetaData meta = rs.getMetaData();
        int cols = meta.getColumnCount();
        while (rs.next()) {
            Map<String,Object> row = new LinkedHashMap<>();
            for (int i = 1; i <= cols; i++) row.put(meta.getColumnName(i), rs.getObject(i));
            list.add(row);
        }
        return list;
    }

    static Map<String,Object> rowToMap(ResultSet rs, String... cols) throws SQLException {
        Map<String,Object> map = new LinkedHashMap<>();
        for (String col : cols) map.put(col, rs.getObject(col));
        return map;
    }

    static String success(String message, Object data) {
        Map<String,Object> r = new LinkedHashMap<>();
        r.put("success", true);
        r.put("message", message);
        r.put("data", data);
        return gson.toJson(r);
    }

    static String error(String message) {
        return gson.toJson(Map.of("success", false, "error", message));
    }
}
