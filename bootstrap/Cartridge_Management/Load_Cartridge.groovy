// Jobs
def generateLoadCartridgeJob = workflowJob("/Load_Cartridge")

generateLoadCartridgeJob.with {
    parameters
    {
        stringParam("workspaceName","ExampleWorkspace","Name of the workspace to load cartridge in (either existing or new).")
        stringParam("projectName","ExampleProject","Name of the project to load cartridge in (either existing or new).")
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
              @Grab('org.yaml:snakeyaml:1.17')
              import org.yaml.snakeyaml.Yaml
              import org.yaml.snakeyaml.parser.ParserException
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
                  Yaml parser = new Yaml();
                  LinkedHashMap yaml = parser.load(html);
                  yaml.each{ key, cartridge ->
                    cartridge_urls.add(cartridge.url)
                  }
                }
                catch (UnknownHostException e) {
                  cartridge_urls.add("[ERROR] Provided URL was not found: ${source_url}");
                  println "[ERROR] Provided URL was not found: ${source_url}";
                }
                catch (ParserException e) {
                  cartridge_urls.add("[ERROR] Provided URL has invalid YAML: ${source_url}");
                  println "[ERROR] Provided URL has invalid YAML: ${source_url}";
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
    environmentVariables
    {
        groovy("return [SCM_KEY: org.apache.commons.lang.RandomStringUtils.randomAlphanumeric(20)]")
        env('WORKSPACE_NAME',workspaceFolderName)
        env('PROJECT_NAME',projectFolderName)
    }
    wrappers
    {
        preBuildCleanup()
        injectPasswords()
        maskPasswords()
        sshAgent("adop-jenkins-master")
        credentialsBinding {
            file('SCM_SSH_KEY', 'adop-jenkins-private')
        }
    }
    definition
    {
        cps
        {
            script('''// Setup Workspace
                    build job: 'Workspace_Management/Generate_Workspace', parameters: [[$class: 'StringParameterValue', name: 'WORKSPACE_NAME', value: "${workspaceName}"]]

                    // Setup Faculty
                    build job: "${workspaceName}/Project_Management/Generate_Project", parameters: [[$class: 'StringParameterValue', name: 'PROJECT_NAME', value: "${projectName}"]]
                    retry(5)
                    {
                        build job: "${workspaceName}/${projectName}/Cartridge_Management/Load_Cartridge", parameters: [[$class: 'StringParameterValue', name: 'CARTRIDGE_FOLDER', value: "${CARTRIDGE_FOLDER}"], [$class: 'StringParameterValue', name: 'FOLDER_DISPLAY_NAME', value: "${FOLDER_DISPLAY_NAME}"], [$class: 'StringParameterValue', name: 'FOLDER_DESCRIPTION', value: "${FOLDER_DESCRIPTION}"], [$class: 'StringParameterValue', name: 'CARTRIDGE_CLONE_URL', value: "${CARTRIDGE_CLONE_URL}"]]
                    }''')
            sandbox()
        }
    }
}