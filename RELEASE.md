## Release
* verify the current version you want to release in gradle.properties
* verify you are using SSH with GIT
* use openJDK 21 as project JDK
* do **publish** task
* go to *build/repos/releases* on **core**
* remove the "maven-metadata.xml" (and all files in the same directory)
* zip the **dev** dir
* upload manually on https://central.sonatype.com/publishing, use release name *jayo-http-X.Y.Z*
  * do **Publish Component** , refresh, then **Publish**
  * refresh again after several minutes, deployment status must be "PUBLISHED"
* do **release** task (for minor release, press Enter for suggested versions : release version = current,
new version = current + 1)
