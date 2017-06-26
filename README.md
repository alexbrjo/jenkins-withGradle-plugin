# Gradle-plugin fork

Theses are the changes from the gradle-plugin that add pipeline compatibility. 
Because of unresolved issues with transitive dependencies I've had to convert to a 
Maven project for development. 

Before adding these changes back to the `pipeline compat` branch I need to find a patch
for the build.gradle so dependencies are packaged correctly. Currently @abayer has helped 
come up for a fix for compilation, but half of the tests still fail on run and `./gradlew server`
doesn't load the plugin successfully.
