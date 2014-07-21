package org.mobicents.servlet.restcomm.rvd.storage;

import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.log4j.Logger;
import org.mobicents.servlet.restcomm.rvd.RvdSettings;
import org.mobicents.servlet.restcomm.rvd.model.client.Node;
import org.mobicents.servlet.restcomm.rvd.model.client.StateHeader;
import org.mobicents.servlet.restcomm.rvd.model.client.WavItem;
import org.mobicents.servlet.restcomm.rvd.storage.exceptions.BadProjectHeader;
import org.mobicents.servlet.restcomm.rvd.storage.exceptions.BadWorkspaceDirectoryStructure;
import org.mobicents.servlet.restcomm.rvd.storage.exceptions.ProjectDirectoryAlreadyExists;
import org.mobicents.servlet.restcomm.rvd.storage.exceptions.StorageException;
import org.mobicents.servlet.restcomm.rvd.storage.exceptions.WavItemDoesNotExist;
import org.mobicents.servlet.restcomm.rvd.model.client.Step;

import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

public class FsProjectStorage implements ProjectStorage {
    static final Logger logger = Logger.getLogger(FsProjectStorage.class.getName());

    private String workspaceBasePath;
    private String prototypeProjectPath;

    public FsProjectStorage(RvdSettings settings) {
        this.workspaceBasePath = settings.getWorkspaceBasePath();
        this.prototypeProjectPath = settings.getPrototypeProjectsPath();
    }

    public FsProjectStorage(String workspaceBasePath, String prototypeProjectPath) {
        this.workspaceBasePath = workspaceBasePath;
        this.prototypeProjectPath = prototypeProjectPath;
    }

    private String getProjectBasePath(String name) {
        return workspaceBasePath + File.separator + name;
    }

    private String getProjectWavsPath(String projectName) {
        return getProjectBasePath(projectName) + File.separator + RvdSettings.WAVS_DIRECTORY_NAME;
    }

    @Override
    public String loadProjectOptions(String projectName) throws StorageException {
        String filepath = getProjectBasePath(projectName) + File.separator + "data" + File.separator + "project";
        try {
            String projectOptions_json = FileUtils.readFileToString(new File(filepath));
            return projectOptions_json;
        } catch (IOException e) {
            throw new StorageException("Cannot read project options file - " + filepath , e);
        }
    }

    @Override
    public void storeProjectOptions(String projectName, String projectOptions) throws StorageException {
        String filepath = getProjectBasePath(projectName) + File.separator + "data/" + "project";
        File outFile = new File( filepath );
        try {
            FileUtils.writeStringToFile(outFile, projectOptions, "UTF-8");
        } catch (IOException e) {
            throw new StorageException("Cannot write project options file - " + filepath , e);
        }
    }

    @Override
    public void clearBuiltProject(String projectName) {

        String projectPath = getProjectBasePath(projectName) + File.separator + projectName + File.separator;
        File dataDir = new File(projectPath + "data");

        // delete all files in directory
        for (File anyfile : dataDir.listFiles()) {
            anyfile.delete();
        }

    }

    @Override
    public String loadProjectState(String projectName) throws StorageException {
        String filepath = getProjectBasePath(projectName) + File.separator + "state";
        try {
            return FileUtils.readFileToString(new File(filepath), "UTF-8");
        } catch (IOException e) {
            throw new StorageException("Error loading project state file - " + filepath , e);
        }
    }

    /*
    @Override
    public String loadNodeStepnames(String projectName, String nodeName) throws StorageException {
        String content;
        String filepath = getProjectBasePath(projectName) + File.separator + "data/" + nodeName + ".node";
        try {
            content = FileUtils.readFileToString(new File(filepath));
            return content;
        } catch (IOException e) {
            throw new StorageException("Error reading file - " + filepath);
        }
    }

    @Override
    public void storeNodeStepnames(String projectName,String nodeName, String stepNames) throws StorageException {
        String filepath = getProjectBasePath(projectName) + File.separator + "data/" + nodeName + ".node";
        File outFile = new File(filepath);
        try {
            FileUtils.writeStringToFile(outFile, stepNames, "UTF-8");
        } catch (IOException e) {
            throw new StorageException("Error writing stepnames file - " + filepath , e);
        }
    }
    */

    @Override
    public void storeNodeStep(String projectName, String nodeName, String stepName, String content) throws StorageException {
        String filepath = getProjectBasePath(projectName) + File.separator + "data/" + nodeName + "." + stepName;
        try {
            FileUtils.writeStringToFile(new File(filepath), content, "UTF-8");
        } catch (IOException e) {
            throw new StorageException("Error writing module step file - " + filepath , e);
        }

    }

    @Override
    public boolean projectExists(String projectName) {
        File projectDir = new File(workspaceBasePath + File.separator + projectName);
        if (projectDir.exists())
            return true;
        return false;
    }

    @Override
    public List<String> listProjectNames() throws BadWorkspaceDirectoryStructure {
        List<String> items = new ArrayList<String>();

        File workspaceDir = new File(workspaceBasePath);
        if (workspaceDir.exists()) {

            File[] entries = workspaceDir.listFiles(new FileFilter() {
                @Override
                public boolean accept(File anyfile) {
                    if (anyfile.isDirectory() && !anyfile.getName().startsWith(RvdSettings.PROTO_DIRECTORY_PREFIX))
                        return true;
                    return false;
                }
            });
            Arrays.sort(entries, new Comparator<File>() {
                public int compare(File f1, File f2) {
                    File statefile1 = new File(f1.getAbsolutePath() + File.separator + "state");
                    File statefile2 = new File(f2.getAbsolutePath() + File.separator + "state");
                    if ( statefile1.exists() && statefile2.exists() )
                        return Long.valueOf(statefile2.lastModified()).compareTo(statefile1.lastModified());
                    else
                        return Long.valueOf(f2.lastModified()).compareTo(f1.lastModified());
                }
            });

            for (File entry : entries) {
                items.add(entry.getName());
            }
        } else
            throw new BadWorkspaceDirectoryStructure();

        return items;
    }

    /**
     * A low-level function to copy projects. It can be used both for creating new projects out of prototype projects
     * or simply cloning projects
     * @param sourceDir
     * @param destDir
     * @throws IOException
     * @throws ProjectDirectoryAlreadyExists
     */
    private void cloneProject(File sourceDir, File destDir) throws IOException, ProjectDirectoryAlreadyExists {
            if (!destDir.exists()) {
                    FileUtils.copyDirectory(sourceDir, destDir);
                    // set the modified date of the "state" file to reflect the fact that we are dealing with a new project
                    File statefile = new File(destDir.getAbsolutePath() + File.separator + "state");
                    if ( statefile.exists() )
                        statefile.setLastModified(new Date().getTime());
            } else {
                throw new ProjectDirectoryAlreadyExists();
            }
    }

    @Override
    public void cloneProject(String name, String clonedName) throws StorageException {
        File sourceDir = new File(workspaceBasePath + File.separator + name);
        File destDir = new File(workspaceBasePath + File.separator + clonedName);
        try {
            cloneProject( sourceDir, destDir);
        } catch (IOException e) {
            throw new StorageException("Error cloning project '" + name + "' to '" + clonedName + "'" , e);
        }
    }

    @Override
    public void cloneProtoProject(String kind, String clonedName) throws StorageException {
        String protoProjectPath = prototypeProjectPath + File.separator + RvdSettings.PROTO_DIRECTORY_PREFIX + "_" + kind;
        File sourceDir = new File( protoProjectPath );
        String destProjectPath = workspaceBasePath + File.separator + clonedName;
        File destDir = new File(destProjectPath);
        try {
            cloneProject( sourceDir, destDir);
        } catch (IOException e) {
            throw new StorageException("Error cloning project '" + protoProjectPath + "' to '" + destProjectPath + "'" , e);
        }
    }

    @Override
    public void updateProjectState(String projectName, String newState) throws StorageException {
        FileOutputStream stateFile_os;
        try {
            stateFile_os = new FileOutputStream(workspaceBasePath + File.separator + projectName + File.separator + "state");
            IOUtils.write(newState, stateFile_os);
            stateFile_os.close();
        } catch (FileNotFoundException e) {
            throw new StorageException("Error updating state file for project '" + projectName + "'", e);
        } catch (IOException e) {
            throw new StorageException("Error updating state file for project '" + projectName + "'", e);
        }
    }

    @Override
    public void renameProject(String projectName, String newProjectName) throws StorageException {
        try {
            File sourceDir = new File(workspaceBasePath + File.separator + projectName);
            File destDir = new File(workspaceBasePath + File.separator + newProjectName);
            FileUtils.moveDirectory(sourceDir, destDir);
        } catch (IOException e) {
            throw new StorageException("Error renaming directory '" + projectName + "' to '" + newProjectName + "'");
        }
    }

    @Override
    public void deleteProject(String projectName) throws StorageException {
        try {
            File projectDir = new File(workspaceBasePath + File.separator + projectName);
            FileUtils.deleteDirectory(projectDir);
        } catch (IOException e) {
            throw new StorageException("Error removing directory '" + projectName + "'", e);
        }
    }

    @Override
    public void storeWav(String projectName, String wavname, InputStream wavStream) throws StorageException {
        String wavPathname = getProjectWavsPath(projectName) + File.separator + wavname;
        logger.debug( "Writing wav file to " + wavPathname);
        try {
            FileUtils.copyInputStreamToFile(wavStream, new File(wavPathname) );
        } catch (IOException e) {
            throw new StorageException("Error writing to " + wavPathname, e);
        }
    }

    /**
     * Returns an InputStream to the wav specified or throws an error if not found. DON'T FORGET TO CLOSE the
     * input stream after using. It is actually a FileInputStream.
     */
    @Override
    public InputStream getWav(String projectName, String filename) throws StorageException {
        String wavpath = getProjectBasePath(projectName) + File.separator + RvdSettings.WAVS_DIRECTORY_NAME + File.separator + filename;
        File wavfile = new File(wavpath);
        if ( wavfile.exists() )
            try {
                return new FileInputStream(wavfile);
            } catch (FileNotFoundException e) {
                throw new StorageException("Error reading wav: " + filename, e);
            }
        else
            throw new WavItemDoesNotExist("Wav file does not exist - " + filename );

    }

    @Override
    public List<WavItem> listWavs(String projectName) throws StorageException {
        List<WavItem> items = new ArrayList<WavItem>();

        //File workspaceDir = new File(workspaceBasePath + File.separator + appName + File.separator + "wavs");
        File wavsDir = new File(getProjectWavsPath(projectName));
        if (wavsDir.exists()) {

            File[] entries = wavsDir.listFiles(new FileFilter() {
                @Override
                public boolean accept(File anyfile) {
                    if (anyfile.isFile())
                        return true;
                    return false;
                }
            });
            Arrays.sort(entries, new Comparator<File>() {
                public int compare(File f1, File f2) {
                    return Long.valueOf(f1.lastModified()).compareTo(f2.lastModified()) ;
                }
            });

            for (File entry : entries) {
                WavItem item = new WavItem();
                item.setFilename(entry.getName());
                items.add(item);
            }
        } else
            throw new BadWorkspaceDirectoryStructure();

        return items;
    }

    @Override
    public void deleteWav(String projectName, String wavname) throws WavItemDoesNotExist {
        String filepath = getProjectWavsPath(projectName) + File.separator + wavname;
        File wavfile = new File(filepath);
        if ( wavfile.delete() )
            logger.info( "Deleted " + wavname + " from " + projectName + " app" );
        else {
            //logger.warn( "Cannot delete " + wavname + " from " + projectName + " app" );
            throw new WavItemDoesNotExist("Wav file does not exist - " + filepath );
        }

    }

    @Override
    public String loadStep(String projectName, String nodeName, String stepName) throws StorageException {
        String filepath = getProjectBasePath(projectName) + File.separator + "data/" + nodeName + "." + stepName;
        try {
            return FileUtils.readFileToString(new File(filepath));
        } catch (IOException e) {
            throw new StorageException("Error reading from file " + filepath);
        }
    }

    @Override
    public StateHeader loadStateHeader(String projectName) throws StorageException {
        JsonParser parser = new JsonParser();
        JsonElement header_element = parser.parse(loadProjectState(projectName)).getAsJsonObject().get("header");
        if ( header_element == null )
            throw new BadProjectHeader("No header found. This is probably an old project");

        Gson gson = new Gson();
        StateHeader header = gson.fromJson(header_element, StateHeader.class);

        return header;
    }

    @Override
    public void storeNodeStepnames(String projectName, Node node) throws StorageException {
        List<String> stepnames = new ArrayList<String>();
        for ( Step step : node.getSteps() ) {
            stepnames.add(step.getName());
        }

        String filepath = getProjectBasePath(projectName) + File.separator + "data/" + node.getName() + ".node";
        File outFile = new File(filepath);

        Gson gson = new Gson();
        String stepnamesString = gson.toJson(stepnames);

        try {
            FileUtils.writeStringToFile(outFile, stepnamesString, "UTF-8");
        } catch (IOException e) {
            throw new StorageException("Error writing node file - " + filepath , e);
        }
    }

    @Override
    public List<String> loadNodeStepnames(String projectName, String nodeName) throws StorageException {
        String content;
        String filepath = getProjectBasePath(projectName) + File.separator + "data/" + nodeName + ".node";
        try {
            content = FileUtils.readFileToString(new File(filepath));

            Gson gson = new Gson();
            List<String> stepnames = gson.fromJson(content, new TypeToken<List<String>>(){}.getType());
            return stepnames;
        } catch (IOException e) {
            throw new StorageException("Error reading node file - " + filepath);
        }
    }

    @Override
    public void backupProjectState(String projectName) throws StorageException {
        File sourceStateFile = new File(workspaceBasePath + File.separator + projectName + File.separator + "state");
        File backupStateFile = new File(workspaceBasePath + File.separator + projectName + File.separator + "state" + ".old");

        try {
            FileUtils.copyFile(sourceStateFile, backupStateFile);
        } catch (IOException e) {
            throw new StorageException("Error creating state file backup: " + backupStateFile);
        }
    }
}