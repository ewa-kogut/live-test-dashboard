# Live Test Dashboard

Watch a JUnit 5 test run live in your browser: progress such as "12 of 24", passed and failed
counts, a ring chart of the results, the time each test took, and each failure's error message. Every
run is saved with its own link, so you can reopen it at any time.

It has no runtime dependencies and needs no changes to the project under test. It works alongside
other reporting tools, such as Allure and ReportPortal.

## How it works

```text
Your tests → JUnit listener → local server → page in your browser
```

- `DashboardListener` is a standard JUnit listener. JUnit finds it on the classpath.
- `DashboardServer` receives the events and streams them to open pages (Server-Sent Events).
- Each run is saved as one file, so results survive a server restart.
- The page is plain HTML, CSS and JavaScript, with charts drawn as SVG.

## Requirements

- Java 21, to build the jar and run the server.
- A test project that uses JUnit 5 and Maven Surefire. Other setups are untested.
- Tests and server on the same computer, because the server listens on this computer only.

No database or other software is needed. The commands below are for PowerShell on Windows.

## Set up

**1. Get the code**

```powershell
git clone https://github.com/ewa-kogut/live-test-dashboard.git
cd live-test-dashboard
```

**2. Build the jar**

```powershell
.\mvnw -B package
```

This creates `target\live-test-dashboard.jar`. Windows locks the jar while the server is running, so
stop the server before you build again.

## Connect your repository

There is nothing to change or commit in the repository you want to test: no dependency, no code and no
configuration. You add the jar only when you run the tests, using its full path:

```text
-Dmaven.test.additionalClasspath=C:\path\to\live-test-dashboard.jar
```

JUnit then finds the dashboard's listener by itself. Without this option, your tests run as they always
did.

## Run on your computer

**1. Start the server** in its own terminal, and leave the terminal open:

```powershell
java -jar target\live-test-dashboard.jar
```

To use another port, add it to the command, e.g. `java -jar target\live-test-dashboard.jar 9000`, and
add `-Ddashboard.url=http://localhost:9000` to the test command. To stop the server, press `Ctrl+C`.

**2. Open** http://localhost:8095

**3. Run your tests** in a second terminal, from the folder of the project you want to test:

```powershell
.\mvnw -B test "-Dmaven.test.additionalClasspath=C:\path\to\live-test-dashboard.jar"
```

The page updates as the tests run. Earlier runs are under *Run history*.

## Run in Jenkins

This is an ordinary addition to your pipeline, not a Jenkins plugin. Jenkins must run on the same
computer as the server. The steps assume a Windows machine, and a Pipeline job that reads a `Jenkinsfile`
from your repository.

**1. Build the jar and start the server** as described above. The server must be running before the
build starts. Jenkins only needs to be able to read the jar, so put it in a folder that the Jenkins
service account can access.

**2. Add this to your `Jenkinsfile`**, above `pipeline {`, with your own jar path:

```groovy
def dashboardJar() {
  return 'C:/path/to/live-test-dashboard.jar'
}

// True when DASHBOARD is ticked and the jar is on the Jenkins machine.
def dashboardEnabled() {
  if (!params.DASHBOARD) {
    return false
  }
  if (!fileExists(dashboardJar())) {
    echo "Dashboard: jar not found at ${dashboardJar()}. This build runs without the dashboard."
    return false
  }
  return true
}

// One ID for the whole build, so all its test stages appear together as one run.
def dashboardRunId() {
  String job = env.JOB_NAME.replaceAll(/[^A-Za-z0-9_-]/, '-')
  String shortJob = job.length() > 40 ? job.substring(0, 40) : job
  return "${shortJob}-${env.BUILD_NUMBER}"
}

def dashboardArgs() {
  if (!dashboardEnabled()) {
    return ''
  }
  String classpath = "\"-Dmaven.test.additionalClasspath=${dashboardJar()}\""
  return " ${classpath} -Ddashboard.run.id=${dashboardRunId()}"
}

// Adds the link to the build page and the Console Output.
def linkDashboard() {
  if (dashboardEnabled()) {
    String url = "http://localhost:8095/?run=${dashboardRunId()}"
    echo "Dashboard for this build: ${url}"
    currentBuild.description = "<a href=\"${url}\">Dashboard</a>"
  }
}
```

**3. Use it in your pipeline.** Add the tick box, add the link in the first stage, and add
`dashboardArgs()` to every Maven command that runs tests:

```groovy
pipeline {
  agent any

  parameters {
    booleanParam(
        name: 'DASHBOARD',
        defaultValue: false,
        description: 'Send results to Live Test Dashboard and add a link to this build.')
  }

  stages {
    stage('Tests') {
      steps {
        script {
          linkDashboard()
        }
        bat "mvnw.cmd -B test${dashboardArgs()}"
      }
    }
  }
}
```

In Groovy, a line that starts with `+` is a new statement, not a continuation of the line above. Keep
the Maven command on one line, or the build fails with `String.positive()`.

**4. Commit and push the `Jenkinsfile`.** Jenkins reads it from your repository, so local changes have
no effect on a build.

**5. Make the link clickable.** Open *Manage Jenkins → Security*, set **Markup Formatter** to
**Safe HTML** and click **Save**. If this option is missing, install the **OWASP Markup Formatter**
plugin. You only do this once.

**6. Start a build.** Jenkins adds a new tick box only after it has read the new `Jenkinsfile`, so run
one build first if you don't see **Build with Parameters** or **DASHBOARD**. Then click **Build with
Parameters**, tick **DASHBOARD** and click **Build**.

**7. Open the Dashboard link** on the build page. The same link is in the Console Output, on the line
that starts with `Dashboard for this build:`. It works only on the computer that runs the server.

### If something goes wrong in Jenkins

| What you see | What to do |
| --- | --- |
| No **DASHBOARD** tick box | Check that the commit is pushed, then run one build. |
| `Checking out Revision` shows an old commit | Jenkins builds what is on the remote. Push your commit. |
| `<a href=...>` shown as text | Set **Markup Formatter** to **Safe HTML** (step 5). |
| `Dashboard: jar not found` | Correct the path in `dashboardJar()`, and check that Jenkins can read the file. |
| The link opens *Run not found*, or the page won't load | Start the server, then run the build again. |

## Settings

| Setting | Default | Purpose |
| --- | --- | --- |
| `-Ddashboard.url` or `DASHBOARD_URL` | `http://localhost:8095` | Where the listener sends results. `off` turns it off. |
| `-Ddashboard.run.id` | a new ID for each test JVM | Puts several test JVMs in one run, such as the stages of a CI build. Letters, digits, `-` and `_` only, up to 64 characters. |
| `-Ddashboard.data.dir` (when starting the server) | `.live-test-dashboard/runs` in your home folder | Where runs are saved. |

## Behaviour

- **Dashboard not running:** the listener prints one line and stops sending. Tests are not affected.
- **New run:** the *Latest run* page clears and follows it. The previous run stays in *Run history*.
- **Run history:** every run has its own link, `/?run=<id>`. The newest 100 runs are kept, and older ones
  are deleted automatically.
- **Page opened mid-run:** it first receives everything it missed.
- **Reruns (Surefire `rerunFailingTestsCount`):** a rerun updates the same test. A test that fails and
  then passes is shown as "Passed on retry".
- **Total count:** it can grow during a run, because JUnit registers the variants of parameterized
  tests only when they start.
- **Several test JVMs:** each JVM is saved as its own run, unless they share `-Ddashboard.run.id`.

## Security

The server listens on this computer only and has no login, because failure messages can contain test
data. Saved runs contain those messages too, so do not publish the runs folder or expose the server on
a network.

## Accessibility

Results are shown with icons and labels, and failures are marked with a cross on the chart,
so colour is never the only signal. Red and green are hard to tell apart with red-green colour
blindness, so the page also has a blue and orange palette. It follows the system's light or dark
setting, the chart can be read with the keyboard, and the test list holds the same results as text.

## Development

```powershell
.\mvnw -B test
```

## License

[MIT](LICENSE)
