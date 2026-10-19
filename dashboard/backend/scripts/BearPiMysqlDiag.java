import java.sql.*;
public class BearPiMysqlDiag {
    public static void main(String[] args) throws Exception {
        String password = System.getenv("DB_PASSWORD");
        if (args.length == 1 && (args[0].equals("--prompt") || args[0].equals("--start"))) {
            java.io.Console console = System.console();
            if (console == null) {
                System.out.println("Interactive terminal required; run this command directly in your WSL terminal.");
                System.exit(2);
            }
            char[] entered = console.readPassword("MySQL password (hidden): ");
            if (entered == null) System.exit(2);
            String supplied = new String(entered);
            java.util.Arrays.fill(entered, '\0');
            if (args[0].equals("--prompt")) {
                System.out.println("Entered password matches DB_PASSWORD=" + supplied.equals(password));
            }
            password = supplied;
        }
        System.out.println("Java DB_PASSWORD exported=" + (password != null) + ", nonempty=" + (password != null && !password.isEmpty()));
        if (password == null || password.isEmpty()) {
            System.out.println("No authentication attempted; run from the WSL shell where DB_PASSWORD was exported.");
            System.exit(2);
        }
        String url = "jdbc:mysql://127.0.0.1:3306/posture_monitor?connectionTimeZone=UTC&connectTimeout=5000&socketTimeout=5000";
        try (Connection c = DriverManager.getConnection(url, "posture_app", password)) {
            c.setReadOnly(true);
            System.out.println("JDBC authentication: SUCCESS");
            System.out.println("Driver: " + c.getMetaData().getDriverVersion());
            try (Statement st = c.createStatement(); ResultSet r = st.executeQuery(
                "SELECT @@server_uuid AS server_uuid, @@hostname AS hostname, @@port AS port, USER() AS client_identity, CURRENT_USER() AS matched_account, DATABASE() AS db, CONNECTION_ID() AS connection_id")) {
                ResultSetMetaData m = r.getMetaData();
                while (r.next()) for (int i=1;i<=m.getColumnCount();i++) System.out.println(m.getColumnLabel(i)+"="+r.getString(i));
            }
        } catch (SQLException e) {
            System.out.println("JDBC authentication/query FAILED; SQLState="+e.getSQLState()+"; errorCode="+e.getErrorCode());
            System.exit(1);
        }
        if (args.length == 1 && args[0].equals("--start")) {
            System.out.println("JDBC verified. Starting Spring Boot with the same password via child environment.");
            ProcessBuilder pb = new ProcessBuilder("mvn", "spring-boot:run");
            // start-backend.sh changes to its own directory before invoking Java.
            java.nio.file.Path backendDir = java.nio.file.Path.of("").toAbsolutePath();
            if (!java.nio.file.Files.isRegularFile(backendDir.resolve("pom.xml"))) {
                System.err.println("Run bash start-backend.sh from the backend project.");
                System.exit(2);
            }
            pb.directory(backendDir.toFile());
            pb.environment().put("DB_PASSWORD", password);
            pb.inheritIO();
            Process child = pb.start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> child.destroy()));
            System.exit(child.waitFor());
        }
    }
}
