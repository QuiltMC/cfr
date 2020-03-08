pipeline {
   agent any
   stages {

      stage('Init') {
         steps {
            sh "rm -rf build/libs/"
            sh "chmod +x gradlew"
         }
      }

      stage ('Build and Publish') {
         steps {
            sh "./gradlew build publish --refresh-dependencies --stacktrace"

            archiveArtifacts artifacts: 'build/libs/*.jar', fingerprint: true
         }
      }
   }
}