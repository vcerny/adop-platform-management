// Constants
def platformToolsGitUrl = "https://github.com/IrmantasM/adop-platform-management.git"
def scmPropertiesPath = "${PLUGGABLE_SCM_PROVIDER_PROPERTIES_PATH}"

// Folders
def workspaceFolderName = "${WORKSPACE_NAME}"

// Dynamic values
def customScmNamespace = "${CUSTOM_SCM_NAMESPACE}"
String namespaceValue = null
if (customScmNamespace == "true"){
    namespaceValue = '"${SCM_NAMESPACE}"'
} else {
    namespaceValue = 'null'
}

def projectFolderName = workspaceFolderName + "/${PROJECT_NAME}"
def projectFolder = folder(projectFolderName)

def cartridgeManagementFolderName= projectFolderName + "/Cartridge_Management"
def cartridgeManagementFolder = folder(cartridgeManagementFolderName) { displayName('Cartridge Management') }

// Jobs
def loadCartridgeJob = freeStyleJob(cartridgeManagementFolderName + "/Load_Cartridge")
def loadCartridgeCollectionJob = workflowJob(cartridgeManagementFolderName + "/Load_Cartridge_Collection")


// Setup Load_Cartridge
loadCartridgeJob.with{
    parameters{
        activeChoiceParam('SCM_PROVIDER') {
            description('Your chosen SCM Provider and the appropriate cloning protocol')
            filterable()
            choiceType('SINGLE_SELECT')
            groovyScript {
                script('''
					|import hudson.model.*;
					|import hudson.util.*;
					|
					|base_path = "/var/jenkins_home/userContent/datastore/pluggable/scm"
					|
					|// Initialise folder containing all SCM provider properties files
					|String PropertiesPath = base_path + "/ScmProviders/"
					|File folder = new File(PropertiesPath)
					|def providerList = []
					|
					|// Loop through all files in properties data store and add to returned list
					|for (File fileEntry : folder.listFiles()) {
					|if (!fileEntry.isDirectory()){
					|String title = PropertiesPath +  fileEntry.getName()
					|Properties scmProperties = new Properties()
					|InputStream input = null
					|input = new FileInputStream(title)
					|scmProperties.load(input)
					|String url = scmProperties.getProperty("scm.url")
					|String protocol = scmProperties.getProperty("scm.protocol")
					|String id = scmProperties.getProperty("scm.id")
					|String output = url + " - " + protocol + " (" + id + ")"
					|providerList.add(output)
					|}
					|}
					|
					|if (providerList.isEmpty()) {
					|providerList.add("No SCM providers found")
					|}
					|
					|return providerList;
					'''.stripMargin())
                fallbackScript('''
					|import hudson.model.*;
					|import hudson.util.*;
					|
					|base_path = "/var/jenkins_home/userContent/datastore/pluggable/scm"
					|
					|// Initialise folder containing all SCM provider properties files
					|String PropertiesPath = base_path + "/ScmProviders/"
					|File folder = new File(PropertiesPath)
					|def providerList = []
					|
					|// Loop through all files in properties data store and add to returned list
					|for (File fileEntry : folder.listFiles()) {
					|if (!fileEntry.isDirectory()){
					|String title = PropertiesPath +  fileEntry.getName()
					|Properties scmProperties = new Properties()
					|InputStream input = null
					|input = new FileInputStream(title)
					|scmProperties.load(input)
					|String url = scmProperties.getProperty("scm.url")
					|String protocol = scmProperties.getProperty("scm.protocol")
					|String id = scmProperties.getProperty("scm.id")
					|String output = url + " - " + protocol + " (" + id + ")"
					|providerList.add(output)
					|}
					|}
					|
					|if (providerList.isEmpty()) {
					|providerList.add("No SCM providers found")
					|}
					|
					|return providerList;
					'''.stripMargin())
            }
        }
        if (customScmNamespace == "true"){
            stringParam('SCM_NAMESPACE', '', 'The namespace for your SCM provider which will prefix your created repositories')
        }
        extensibleChoiceParameterDefinition {
            name('CARTRIDGE_CLONE_URL')
            choiceListProvider {
                systemGroovyChoiceListProvider {
                    scriptText('''
              import jenkins.model.*
              nodes = Jenkins.instance.globalNodeProperties
              nodes.getAll(hudson.slaves.EnvironmentVariablesNodeProperty.class)
              envVars = nodes[0].envVars
              def URLS = envVars['CARTRIDGE_SOURCES'];
              if (URLS == null) {
                println "[ERROR] CARTRIDGE_SOURCES Jenkins environment variable has not been set";
                return ['Type the cartridge URL (or add CARTRIDGE_SOURCES as a Jenkins environment variable if you wish to see a list here)'];
              }
              if (URLS.length() < 11) {
                println "[ERROR] CARTRIDGE_SOURCES Jenkins environment variable does not seem to contain valid URLs";
                return ['Type the cartridge URL (the CARTRIDGE_SOURCES Jenkins environment variable does not seem valid)'];
              }
              def cartridge_urls = [];
              URLS.split(';').each{ source_url ->
                try {
                  def html = source_url.toURL().text;
                  html.eachLine { line ->
                    if (line.contains("url:")) {
                      def url = line.substring(line.indexOf("\"") + 1, line.lastIndexOf("\""))
                      cartridge_urls.add(url)
                    }
                  }
                }
                catch (UnknownHostException e) {
                  cartridge_urls.add("[ERROR] Provided URL was not found: ${source_url}");
                  println "[ERROR] Provided URL was not found: ${source_url}";
                }
                catch (Exception e) {
                  cartridge_urls.add("[ERROR] Unknown error while processing: ${source_url}");
                  println "[ERROR] Unknown error while processing: ${source_url}";
                }
              }
              return cartridge_urls;
''')
                    defaultChoice('Top')
                    usePredefinedVariables(false)
                }
            }
            editable(true)
            description('Cartridge URL to load')
        }
        stringParam('CARTRIDGE_FOLDER', '', 'The folder within the project namespace where your cartridge will be loaded into.')
        stringParam('FOLDER_DISPLAY_NAME', '', 'Display name of the folder where the cartridge is loaded.')
        stringParam('FOLDER_DESCRIPTION', '', 'Description of the folder where the cartridge is loaded.')
        booleanParam('ENABLE_CODE_REVIEW', false, 'Enables Gerrit Code Reviewing for the selected cartridge')
        booleanParam('OVERWRITE_REPOS', false, 'If ticked, existing code repositories (previously loaded by the cartridge) will be overwritten. For first time cartridge runs, this property is redundant and will perform the same behavior regardless.')
    }
    environmentVariables {
        groovy("return [SCM_KEY: org.apache.commons.lang.RandomStringUtils.randomAlphanumeric(20)]")
        env('WORKSPACE_NAME',workspaceFolderName)
        env('PROJECT_NAME',projectFolderName)
    }
    wrappers {
        preBuildCleanup()
        injectPasswords()
        maskPasswords()
        credentialsBinding {
            file('SCM_SSH_KEY', 'adop-jenkins-private')
        }
    }
    steps {
        shell('''#!/bin/bash -ex

# Create temp directory for repositories
mkdir ${WORKSPACE}/tmp

# Copy pluggable SCM package into workspace
mkdir ${WORKSPACE}/job_dsl_additional_classpath
cp -r ${PLUGGABLE_SCM_PROVIDER_PATH}pluggable $WORKSPACE/job_dsl_additional_classpath

# Output SCM provider ID to a properties file
echo SCM_PROVIDER_ID=$(echo ${SCM_PROVIDER} | cut -d "(" -f2 | cut -d ")" -f1) > scm_provider_id.properties
''')
        environmentVariables {
            propertiesFile('scm_provider_id.properties')
        }
        systemGroovyCommand('''import pluggable.scm.PropertiesSCMProviderDataStore
import pluggable.scm.SCMProviderDataStore
import pluggable.configuration.EnvVarProperty;
import pluggable.scm.helpers.HelperUtils
import java.util.Properties
import hudson.FilePath


String scmProviderId = build.getEnvironment(listener).get('SCM_PROVIDER_ID')
EnvVarProperty envVarProperty = EnvVarProperty.getInstance();


envVarProperty.setVariableBindings(build.getEnvironment(listener));
SCMProviderDataStore scmProviderDataStore = new PropertiesSCMProviderDataStore();
Properties scmProviderProperties = scmProviderDataStore.get(scmProviderId);

// get credentials

String credentialId = scmProviderProperties.get("loader.credentialId")

if(credentialId != null){
  credentialInfo = HelperUtils.extractPasswordCredentials(credentialId);
  channel = build.workspace.channel;
  fp = new FilePath(channel, build.workspace.toString() + "/" + build.getEnvVars()["SCM_KEY"])
  fp.write("SCM_USERNAME="+credentialInfo[0]+"\\nSCM_PASSWORD="+credentialInfo[1], null);
}
'''){
            classpath('$WORKSPACE/job_dsl_additional_classpath/')
        }
        shell('''#!/bin/bash -ex

# We trust everywhere
echo -e "#!/bin/sh
exec ssh -i ${SCM_SSH_KEY} -o StrictHostKeyChecking=no \"\\\$@\"
" > ${WORKSPACE}/custom_ssh
chmod +x ${WORKSPACE}/custom_ssh
export GIT_SSH="${WORKSPACE}/custom_ssh"

# Clone Cartridge
echo "INFO: cloning ${CARTRIDGE_CLONE_URL}"
# we don't want to show the password
set +x
if ( [ ${CARTRIDGE_CLONE_URL%://*} == "https" ] ||  [ ${CARTRIDGE_CLONE_URL%://*} == "http" ] ) && [ -f ${WORKSPACE}/${SCM_KEY} ]; then
	source ${WORKSPACE}/${SCM_KEY}
	git clone ${CARTRIDGE_CLONE_URL%://*}://${SCM_USERNAME}:${SCM_PASSWORD}@${CARTRIDGE_CLONE_URL#*://} cartridge
else
    git clone ${CARTRIDGE_CLONE_URL} cartridge
fi
set -x

# Find the cartridge
export CART_HOME=$(dirname $(find -name metadata.cartridge | head -1))


# Output SCM provider ID to a properties file
echo GIT_SSH="${GIT_SSH}" >> scm_provider.properties

# Provision one-time infrastructure
if [ -d ${WORKSPACE}/${CART_HOME}/infra ]; then
    cd ${WORKSPACE}/${CART_HOME}/infra
    if [ -f provision.sh ]; then
        source provision.sh
    else
        echo "INFO: ${CART_HOME}/infra/provision.sh not found"
    fi
fi

# Generate Jenkins Jobs
if [ -d ${WORKSPACE}/${CART_HOME}/jenkins/jobs ]; then
    cd ${WORKSPACE}/${CART_HOME}/jenkins/jobs
    if [ -f generate.sh ]; then
        source generate.sh
    else
        echo "INFO: ${CART_HOME}/jenkins/jobs/generate.sh not found"
    fi
fi
''')
        systemGroovyCommand('''
import jenkins.model.*
import groovy.io.FileType

def jenkinsInstace = Jenkins.instance
def projectName = build.getEnvironment(listener).get('PROJECT_NAME')
def mcfile = new FileNameFinder().getFileNames(build.getWorkspace().toString(), '**/metadata.cartridge')
def xmlDir = new File(mcfile[0].substring(0, mcfile[0].lastIndexOf(File.separator))  + "/jenkins/jobs/xml")

def fileList = []

xmlDir.eachFileRecurse (FileType.FILES) { file ->
    if(file.name.endsWith('.xml')) {
        fileList << file
    }
}
fileList.each {
	String configPath = it.path
  	File configFile = new File(configPath)
    String configXml = configFile.text
    ByteArrayInputStream xmlStream = new ByteArrayInputStream( configXml.getBytes() )
    String jobName = configFile.getName().substring(0, configFile.getName().lastIndexOf('.'))

    jenkinsInstace.getItem(projectName,jenkinsInstace).createProjectFromXML(jobName, xmlStream)
}
''')
        dsl {
            text('''import pluggable.scm.*;

// Instantiate your SCM provider where your repos will be created
SCMProvider scmProvider = SCMProviderHandler.getScmProvider("${SCM_PROVIDER_ID}", binding.variables)

def workspace = "${WORKSPACE}"
def projectFolderName = "${PROJECT_NAME}"
def cartridgeFolder = "${CARTRIDGE_FOLDER}"
def overwriteRepos = "${OVERWRITE_REPOS}"
def scmNamespace = ''' + namespaceValue + '''
def codeReviewEnabled = "${ENABLE_CODE_REVIEW}"

String repoNamespace = null;

// Check if a custom SCM namespace has been provided
if (scmNamespace != null && !scmNamespace.isEmpty()){
  println("Custom SCM namespace specified...")
  repoNamespace = scmNamespace
} else {
  // Check if a folder is specified
  println("Custom SCM namespace not specified, using default project namespace...")
  if (cartridgeFolder == ""){
    println("Folder name not specified...")
    repoNamespace = projectFolderName
  } else {
    println("Folder name specified, changing project namespace value..")
    repoNamespace = projectFolderName + "/" + cartridgeFolder
  }
}

// Create your SCM repositories
scmProvider.createScmRepos(workspace, repoNamespace, codeReviewEnabled, overwriteRepos)
            ''')
            additionalClasspath("job_dsl_additional_classpath/")
        }
        conditionalSteps {
            condition {
                shell ('''#!/bin/bash

# Checking to see if folder is specified and project name needs to be updated

if [ -z ${CARTRIDGE_FOLDER} ] ; then
    echo "Folder name not specified, moving on..."
    exit 1
else
    echo "Folder name specified, changing project name value.."
    exit 0
fi
                ''')
            }
            runner('RunUnstable')
            steps {
                environmentVariables {
                    env('CARTRIDGE_FOLDER','${CARTRIDGE_FOLDER}')
                    env('WORKSPACE_NAME',workspaceFolderName)
                    env('PROJECT_NAME',projectFolderName + '/${CARTRIDGE_FOLDER}')
                    env('FOLDER_DISPLAY_NAME','${FOLDER_DISPLAY_NAME}')
                    env('FOLDER_DESCRIPTION','${FOLDER_DESCRIPTION}')
                }
                dsl {
                    text('''// Creating folder to house the cartridge...

def cartridgeFolderName = "${PROJECT_NAME}"
def FolderDisplayName = "${FOLDER_DISPLAY_NAME}"
if (FolderDisplayName=="") {
    println "Folder display name not specified, using folder name..."
    FolderDisplayName = "${CARTRIDGE_FOLDER}"
}
def FolderDescription = "${FOLDER_DESCRIPTION}"
println("Creating folder: " + cartridgeFolderName + "...")

def cartridgeFolder = folder(cartridgeFolderName) {
  displayName(FolderDisplayName)
  description(FolderDescription)
}
                    ''')
                }
            }
        }
        dsl {
            external("cartridge/**/jenkins/jobs/dsl/*.groovy")
            additionalClasspath("job_dsl_additional_classpath")
        }
        shell('rm -f $WORKSPACE/$SCM_KEY')
    }
}


// Setup Load_Cartridge Collection
loadCartridgeCollectionJob.with{
    parameters{
        stringParam('COLLECTION_URL', '', 'URL to a JSON file defining your cartridge collection.')
    }
    properties {
        rebuild {
            autoRebuild(false)
            rebuildDisabled(false)
        }
    }
    environmentVariables {
        env('WORKSPACE_NAME',workspaceFolderName)
        env('PROJECT_NAME',projectFolderName)
    }
    definition {
        cps {
            script('''node {

    sh("wget ${COLLECTION_URL} -O collection.json")

    println "Reading in values from file..."
    cartridges = parseJSON(readFile('collection.json'))

    println(cartridges);
    println "Obtained values locally...";

    cartridgeCount = cartridges.size
    println "Number of cartridges: ${cartridgeCount}"

    def projectWorkspace =  "''' + projectFolderName + '''"
    println "Project workspace: ${projectWorkspace}"

    // For loop iterating over the data map obtained from the provided JSON file
    for (int i = 0; i < cartridgeCount; i++) {
        def cartridge = cartridges.get(i);

        println("Loading cartridge inside folder: " + cartridge.folder)
        println("Cartridge URL: " + cartridge.url)

        build job: projectWorkspace+'/Cartridge_Management/Load_Cartridge', parameters: [[$class: 'StringParameterValue', name: 'CARTRIDGE_FOLDER', value: cartridge.folder], [$class: 'StringParameterValue', name: 'FOLDER_DISPLAY_NAME', value: cartridge.display_name], [$class: 'StringParameterValue', name: 'FOLDER_DESCRIPTION', value: cartridge.desc], [$class: 'StringParameterValue', name: 'CARTRIDGE_CLONE_URL', value: cartridge.url]]
    }

}

@NonCPS
    def parseJSON(text) {
    def slurper = new groovy.json.JsonSlurper();
    Map data = slurper.parseText(text)
    slurper = null

    def cartridges = []
    for ( i = 0 ; i < data.cartridges.size; i++ ) {
        String url = data.cartridges[i].cartridge.url
        String desc = data.cartridges[i].folder.description
        String folder = data.cartridges[i].folder.name
        String display_name = data.cartridges[i].folder.display_name

        cartridges[i] = [
            'url' : url,
            'desc' : desc,
            'folder' : folder,
            'display_name' : display_name
        ]
    }

    data = null

    return cartridges
}
            ''')
            sandbox()
        }
    }
}