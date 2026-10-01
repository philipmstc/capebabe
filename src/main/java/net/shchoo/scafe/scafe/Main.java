package net.shchoo.scafe.scafe;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.*;

import static net.shchoo.scafe.scafe.ConsoleAction.*;

public class Main {

    static int skillCount = 0;
    static int plCount = 0;

    public static void main(String[] args) throws Exception {
        String url = "jdbc:sqlite:C:\\Users\\phili\\AppData\\Roaming\\DBeaverData\\workspace6\\.metadata\\sample-database-sqlite-1\\Chinook.db";
        Connection connection = DriverManager.getConnection(url);
        Collection<Skill> skills = new ArrayList<>();
        if (connection != null) {
//            System.out.println("Connected");
//            PreparedStatement preparedStatement = connection.prepareStatement("select * from skill");
//            boolean execute = preparedStatement.execute();
//            ResultSet rs = preparedStatement.getResultSet();
//            while (rs.next()) {
//                Skill skill = new Skill(rs.getString("name"), new ArrayList<>());
//                skills.add(skill);
//            }
//
//            preparedStatement.close();
//            rs.close();
//
//            preparedStatement = connection.prepareStatement("select * from product");
//            preparedStatement.execute();
//            rs = preparedStatement.getResultSet();
//            Collection<Product> products = new ArrayList<>();
//            while (rs.next()) {
//                Product product = new Product(rs.getString("name"));
//                products.add(product);
//            }
//            for (Product p : products) {
//                System.out.println("Product: " + p.name);
//            }
//
//            preparedStatement.close();
//            rs.close();
//            connection.close();
        }


        Scanner in = new Scanner(System.in);
        Player player = new Player();
        Board board = new Board(skills);
        DEBUG_CREATE_NEW_RANDOM_SKILL.onSelect(player, board, Arrays.asList("30"));
        DEBUG_CREATE_RANDOM_PRODUCT_LINE.onSelect(player, board, Arrays.asList("10"));
        LIST.onSelect(player, board, new ArrayList<>());
//        while (true) {
//            drawBoard(player, board);
//            System.out.print("Choose an action: ");
//            String s = in.nextLine();
//            if (!s.isEmpty()) {
//                for (ConsoleAction ca : ConsoleAction.values()) {
//                    if (ca.input == s.charAt(0)) {
//                        String[] parts = s.trim().split("\\s+");
//                        List<String> additionalInputs = List.of(parts).subList(1, parts.length);
//                        ca.onSelect(player, board, additionalInputs);
//                    }
//                }
//            }
//        }

    }

    private static void drawBoard(Player player, Board board) {
        for (int i = 0; i < 5; i++) {
            String line = "";
            for (int j = 0; j < 5; j++) {
                if (player.x == j - 2 && player.y == i - 2) {
                    line += board.availableActions(player.x + 2, player.y + 2).isEmpty() ? "  *  " : " [#] ";
                } else {
                    line += board.availableActions(j, i).isEmpty() ? "  .  " : "  #  ";
                }
            }
            System.out.println(line);
        }
    }

}