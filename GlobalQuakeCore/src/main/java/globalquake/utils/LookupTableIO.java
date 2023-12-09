package globalquake.utils;

import java.io.*;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import globalquake.core.regions.Regions;

public class LookupTableIO {
    public static void exportLookupTableToFile() {
        List<HashMap<String, Double>> lookupTables = Regions.generateLookupTablesInParallel();
        HashMap<String, Double> lookupTable = new HashMap<>();
        for(HashMap<String, Double> singleLT : lookupTables) {
            lookupTable.putAll(singleLT);
        }

        performExport(lookupTable);
    }

    public static void exportLookupTableToFile(HashMap<String, Double> lookupTable){
        performExport(lookupTable);
    }

    public static void performExport(HashMap<String, Double> lookupTable) {
        String fileName = "lookupTable.dat";
        String resourcesDir = "./src/main/resources/lookup/";

        try {
            Path filePath = Paths.get(resourcesDir + fileName);
            File file = filePath.toFile();
            if (!file.exists()) {
                Files.createDirectories(filePath.getParent());
                Files.createFile(filePath);
            }

            try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(file))) {
                oos.writeObject(lookupTable);
                System.out.println("Lookup table exported to file successfully.");
            } catch (IOException e) {
                System.err.println("Error exporting lookup table: " + e.getMessage());
            }
        } catch (IOException e) {
            System.err.println("Error creating file: " + e.getMessage());
        }
    }



    public static HashMap<String, Double> importLookupTableFromFile() {
        HashMap<String, Double> lookupTable = new HashMap<>();
        String fileName = "lookupTable.dat";
        String resourcesDir = "./src/main/resources/lookup/";

        try {
            Path filePath = Paths.get(resourcesDir + fileName);
            File file = filePath.toFile();
            if (file.exists()) {
                try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(file))) {
                    lookupTable = (HashMap<String, Double>) ois.readObject();
                    System.out.println("Lookup table imported from file successfully.");
                } catch (IOException | ClassNotFoundException e) {
                    System.err.println("Error importing lookup table: " + e.getMessage());
                }
            } else {
                System.err.println("File not found: " + fileName);
                return null;
            }
        } catch (Exception e) {
            System.err.println("Error accessing file: " + e.getMessage());
            return null;
        }

        return lookupTable;
    }

    public static void main(String[] args) {
        performExport(null);
    }

}
