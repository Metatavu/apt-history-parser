package fi.metatavu.apthistoryparser;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class Main {
  
  public static void main(String[] args) {
    if (args.length < 1) {
      System.out.println("Usage: apt-history-parser COMMAND [options] [logfile]");
      System.exit(-1);
    }

    String command = args[0];
    String logFile = "/var/log/apt/history.log";
    int rollbackCount = 1;
    
    if (args.length > 1) {
      logFile = args[args.length - 1];
    }
    
    if (args.length > 2) {
      for (int i = 1; i < args.length - 1; i+=2) {
        switch (args[i]) {
          case "-rc":
            if (args.length > i + 1) {
              rollbackCount = Integer.parseInt(args[i + 1]);
            } else {
              System.err.println("Invalid value for option -rc");
              System.exit(-1);
            }
          break;
          default:
            System.err.println(String.format("Unknown options", args[i]));
            System.exit(-1);
          break;
        }
      }
    }
    
    List<PackageChangeSet> changeSets = new ArrayList<>();
    
    try (BufferedReader bufferedReader = new BufferedReader(new FileReader(logFile))) {
      PackageChangeSet changes;
      while ((changes = readNextPackageChangeSet(bufferedReader)) != null) {
        changeSets.add(changes);
      }
    } catch (FileNotFoundException e) {
      System.err.println("Log file not found");
      e.printStackTrace();
      System.exit(-1);
    } catch (IOException e) {
      System.err.println("Error reading log file");
      e.printStackTrace();
      System.exit(-1);
    }
    
    switch (command) {
      case  "rollback":
        List<PackageChangeSet> rollbackSets = new ArrayList<>();
        
        for (int rollbackIndex = 0; rollbackIndex < rollbackCount; rollbackIndex++) {
          rollbackSets.add(createrollbackSet(changeSets.get(changeSets.size() - 1 - rollbackIndex)));
        }
        
        Runtime runtime = Runtime.getRuntime();
        
        for (PackageChangeSet rollbackSet : rollbackSets) {
          System.out.println(String.format("Rollback for %s (%s)", rollbackSet.getOriginalCommand(), rollbackSet.getOriginalDate()));
          System.out.println(String.format("  > Running %s...", rollbackSet.getCommand()));
          try {
            Process process = runtime.exec(rollbackSet.getCommand());
            if (process.waitFor() != 0) {
              System.err.println("  > Failed to rollback, see logs for details");
            } else {
              System.err.println("  > Rolled back succesfully");
            }
          } catch (IOException e) {
            System.err.println(String.format("Failed to run command"));
            e.printStackTrace();
            System.exit(-1);
          } catch (InterruptedException e) {
            System.err.println(String.format("Command terminated unexpectedly"));
            e.printStackTrace();
            System.exit(-1);
          }
        }
      break;
      default:
        System.err.println(String.format("Unknown command '%s'", command));
        System.exit(-1);
    }
  }
  
  private static PackageChangeSet readNextPackageChangeSet(BufferedReader bufferedReader) throws IOException {
    List<PackageChange> packageChanges = null;
    String line = null;
    String date = null;
    String command = null;
    
    while ((line = bufferedReader.readLine()) != null) {
      if (line.length() > 0) {
        if (line.startsWith("Start-Date:")) {
          date = leftTrim(line.substring(11));
          packageChanges = new ArrayList<>();
        } else if (line.startsWith("End-Date:")) {
          return new PackageChangeSet(date, command, packageChanges);
        } else {
          if (line.startsWith("Commandline:")) {
            command = leftTrim(line.substring(12));
          } else if (line.startsWith("Install:")) {
            packageChanges.addAll(parseInstalledPackages(line.substring(8)));
          } else if (line.startsWith("Upgrade:")) {
            packageChanges.addAll(parseUpdatedPackages(line.substring(8)));
          } else if (line.startsWith("Remove:")) {
            packageChanges.addAll(parseRemovedPackages(line.substring(7)));
          } else {
            throw new IOException(String.format("Invalid line: '%s'", line));
          }
        }
      }
    }
    
    if (packageChanges == null) {
      return null;
    }
    
    throw new IOException("Unexpected end of file");
  }

  private static PackageChangeSet createrollbackSet(PackageChangeSet changeSet) {
    List<PackageChange> packageChanges = changeSet.getPackageChanges();
    List<PackageChange> result = new ArrayList<>(packageChanges.size());
    
    for (PackageChange packageChange : packageChanges) {
      result.add(packageChange.createRollback());
    }
    
    return new PackageChangeSet(changeSet.getOriginalDate(), changeSet.getOriginalCommand(), result);
  }

  private static List<PackageChange> parseInstalledPackages(String installedPackages) {
    List<PackageChange> result = new ArrayList<>();
    
    for (String installedPackage : installedPackages.split("\\),")) {
      result.add(InstalledPackage.parse(leftTrim(installedPackage)));
    }
    
    return result;
  }

  private static List<PackageChange> parseUpdatedPackages(String updatedPackages) {
    List<PackageChange> result = new ArrayList<>();
    
    for (String updatedPackage : updatedPackages.split("\\),")) {
      result.add(UpgradedPackage.parse(leftTrim(updatedPackage)));
    }
    
    return result;
  }

  private static List<PackageChange> parseRemovedPackages(String removedPackages) {
    List<PackageChange> result = new ArrayList<>();
    
    for (String removedPackage : removedPackages.split("\\),")) {
      result.add(RemovedPackage.parse(leftTrim(removedPackage)));
    }
    
    return result;
  }

  private static abstract class PackageChange {
    
    public PackageChange(String name, String arch) {
      this.name = name;
      this.arch = arch;
    }
    
    public String getArch() {
      return arch;
    }
    
    public String getName() {
      return name;
    }
    
    public abstract String getAptArgument();
    public abstract PackageChange createRollback();
    
    private String name;
    private String arch;
  }
  
  private static class InstalledPackage extends PackageChange {
    
    public InstalledPackage(String name, String arch, String version) {
      super(name, arch);
      this.version = version;
    }
    
    public String getVersion() {
      return version;
    }
    
    public PackageChange createRollback() {
      return new RemovedPackage(getName(), getArch(), getVersion());
    }
    
    @Override
    public String getAptArgument() {
      return String.format("%s=%s", getName(), getVersion());
    }
    
    public static InstalledPackage parse(String packageString) {
      String text = removeEnd(leftTrim(packageString), ")");
      int archIndex = text.indexOf(':');
      int versionIndex = text.lastIndexOf('(');
      String version = removeEnd(text.substring(versionIndex + 1, text.length()), ", automatic");

      return new InstalledPackage(text.substring(0, archIndex), 
          text.substring(archIndex + 1, versionIndex - 1), 
          version);
    }

    private String version;
  }
  
  private static class UpgradedPackage extends PackageChange {
    
    public UpgradedPackage(String name, String arch, String fromVersion, String toVersion) {
      super(name, arch);
      this.fromVersion = fromVersion;
      this.toVersion = toVersion;
    }

    public String getFromVersion() {
      return fromVersion;
    }
    
    public String getToVersion() {
      return toVersion;
    }
    
    public PackageChange createRollback() {
      return new UpgradedPackage(getName(), getArch(), getToVersion(), getFromVersion());
    }
    
    @Override
    public String getAptArgument() {
      return String.format("%s=%s", getName(), getToVersion());
    }

    public static UpgradedPackage parse(String packageString) {
      String text = removeEnd(leftTrim(packageString), ")");
      int archIndex = text.indexOf(':');
      int versionIndex = text.lastIndexOf('(');
      String[] versionString = text.substring(versionIndex + 1, text.length()).split(",", 2);
      
      return new UpgradedPackage(text.substring(0, archIndex), 
          text.substring(archIndex + 1, versionIndex - 1), 
          versionString[0], 
          versionString[1]);
    }
    
    private String fromVersion;
    private String toVersion;
  }
  
  private static class RemovedPackage extends PackageChange {
    
    public RemovedPackage(String name, String arch, String version) {
      super(name, arch);
      this.version = version;
    }
    
    public String getVersion() {
      return version;
    }
    
    public PackageChange createRollback() {
      return new InstalledPackage(getName(), getArch(), getVersion());
    }
    
    @Override
    public String getAptArgument() {
      return String.format("%s-", getName());
    }

    public static RemovedPackage parse(String packageString) {
      String text = removeEnd(leftTrim(packageString), ")");
      int archIndex = text.indexOf(':');
      int versionIndex = text.lastIndexOf('(');
      String version = removeEnd(text.substring(versionIndex + 1, text.length()), ", automatic");

      return new RemovedPackage(text.substring(0, archIndex), 
          text.substring(archIndex + 1, versionIndex - 1), 
          version);
    }
    
    private String version;
  }
  
  private static class PackageChangeSet {
    
    public PackageChangeSet(String originalDate, String originalCommand, List<PackageChange> packageChanges) {
      super();
      this.originalDate = originalDate;
      this.originalCommand = originalCommand;
      this.packageChanges = packageChanges;
    }
    
    public String getOriginalCommand() {
      return originalCommand;
    }
    
    public String getOriginalDate() {
      return originalDate;
    }
    
    public List<PackageChange> getPackageChanges() {
      return packageChanges;
    }
    
    public String getCommand() {
      List<String> aptArguments = new ArrayList<>();
      
      for (PackageChange packageChange : getPackageChanges()) {
        aptArguments.add(packageChange.getAptArgument());
      }
      
      return String.format("apt-get install -y %s", join(aptArguments, " "));
    }
    
    private String originalDate;
    private String originalCommand;
    private List<PackageChange> packageChanges;
  }
  
  private static String join(List<String> strings, String separator) {
    StringBuilder result = new StringBuilder();
    Iterator<String> iterator = strings.iterator();
    while (iterator.hasNext()) {
      result.append(iterator.next());
      if (iterator.hasNext()) {
        result.append(separator);
      }
    }
    
    return result.toString();
  }

  private static String leftTrim(String string) {
    if (string == null) {
      return null;
    }
    
    return string.replaceFirst("\\s*", "");
  }
  
  private static String removeEnd(String string, String suffix) {
    if (string != null && string.endsWith(suffix)) {
      return string.substring(0, string.length() - suffix.length());
    }
    
    return string;
  }
}
