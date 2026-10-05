# Start Mapploy

Open **Git Bash** in this project folder and run:

```bash
bash start-mapploy.sh
```

The launcher starts local Ollama, the Spring Boot backend with the H2 development database, and the Node.js frontend. It waits for readiness and opens **http://127.0.0.1:4173** in your default browser. Existing Mapploy services are reused. You can close the terminal afterwards; the services continue running in the background until stopped or Windows shuts down.

The script also works from another folder when invoked with its full path. It uses the Windows helper at `scripts/start-mapploy.ps1`; keep both files in the project. Use Git Bash, not WSL. No administrator access is needed.

Prerequisites: JDK 25, Node.js 22+ with npm, and Ollama. The launcher finds JDK 25 in `JAVA_HOME` or the usual Windows JDK installation folders. Install the local model once with `ollama pull gemma3:4b`. If the model is missing, the launcher prints a warning and the application can still use its rule-based reports. Frontend dependencies are installed automatically if missing. Initial Maven/npm downloads, job feeds and map tiles need internet access.

For a start without opening another browser window:

```bash
bash start-mapploy.sh --no-browser
```

Services listen on localhost: frontend **4173**, backend **8081**, Ollama **11434**. Logs from each launch are in `.mapploy/logs/`. If startup fails, the launcher reports the log location and stops only the services it started during that failed attempt. It does not stop existing services. Reused services keep their existing configuration; restart them separately after changing application code.
