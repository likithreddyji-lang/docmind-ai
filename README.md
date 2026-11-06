# DocMind — AI Document Intelligence & RAG System

DocMind is a full-stack Retrieval-Augmented Generation (RAG) platform that enables multi-format document ingestion, vector storage, and conversational Q&A powered by Google Gemini and MySQL.

> **Credits:** Original project architecture and design by **LearnCodeWithDurgesh**. Modified and prepared for production-ready, free cloud deployment (Render + Vercel) and local Windows development.

---

## Architecture & Features

- **Backend:** Spring Boot 4.1.0, Java 21, Spring AI 2.0.0, Spring Security (JWT)
- **Database:** MySQL 8+ (`docmind` database) or Cloud MySQL with SSL (TiDB Cloud, Aiven, PlanetScale, Railway)
- **Vector Store:** Custom `MysqlVectorStore` with JSON vector array storage and cosine similarity search in Java
- **AI Models:** Free Google Gemini endpoints (`gemini-embedding-001` for embeddings, `gemini-3.5-flash` for RAG synthesis)
- **Document Support:** PDF, DOCX, TXT, MD, CSV (using Spring AI Apache Tika & PDF Readers)
- **Frontend:** React 19, TypeScript, Vite, Tailwind CSS with dynamic API base URL and SPA client routing

---

## Cloud Deployment Guide (100% Free Tier)

Deploy DocMind for free in 3 steps:

```
[TiDB Cloud / Aiven]  <---(SSL)--->  [Render Web Service]  <---(HTTPS)--->  [Vercel Frontend]
  (Free Cloud MySQL)                     (Spring Boot Docker)                    (React SPA)
```

---

### Step 1: Free Cloud MySQL Database

Obtain a free managed MySQL database with SSL from one of these providers:
- **TiDB Cloud (Serverless):** [tidbcloud.com](https://tidbcloud.com) (5GB free, MySQL 8 compatible, SSL enforced)
- **Aiven for MySQL:** [aiven.io](https://aiven.io) (Free tier available)
- **Railway:** [railway.app](https://railway.app) (Free trial credits)

Create a database named `docmind` and note your connection details:
- **Host**, **Port**, **Username**, **Password**
- Or full **JDBC URL** with SSL, e.g.:
  `jdbc:mysql://<host>:<port>/docmind?sslMode=REQUIRED&useSSL=true&allowPublicKeyRetrieval=true&serverTimezone=UTC`

---

### Step 2: Deploy Backend to Render (Free Web Service)

1. Push your repository to GitHub (ensure no `.env` or secrets are committed).
2. Go to [render.com](https://render.com) and click **New > Web Service**.
3. Connect your GitHub repository.
4. Select **Docker** as the Runtime (Render will automatically detect the root `Dockerfile`).
5. Choose the **Free** instance type.
6. Under **Environment Variables**, add:

| Variable | Value / Description | Example |
|---|---|---|
| `DB_URL` | Full JDBC connection string with SSL | `jdbc:mysql://<host>:3306/docmind?sslMode=REQUIRED&useSSL=true&allowPublicKeyRetrieval=true&serverTimezone=UTC` |
| `DB_USERNAME` | MySQL database username | `root` or cloud user |
| `DB_PASSWORD` | MySQL database password | `your_cloud_db_password` |
| `GEMINI_API_KEY` | Free API key from Google AI Studio | `AIzaSy...` |
| `JWT_SECRET` | 32+ char secret for JWT signing | `a-very-secure-random-32-char-key-here!` |
| `FRONTEND_URL` | Vercel frontend URL (for CORS) | `https://your-docmind-app.vercel.app` |

*(Note: Render automatically injects `PORT`. Spring Boot will bind to it automatically via `${PORT:8081}`).*

Click **Deploy Web Service**. Once live, copy your backend service URL (e.g., `https://docmind-backend.onrender.com`).

---

### Step 3: Deploy Frontend to Vercel (Free Static / SPA)

1. Go to [vercel.com](https://vercel.com) and click **Add New > Project**.
2. Import the same GitHub repository.
3. In the project configuration:
   - **Root Directory:** Click Edit and select `frontend/docmind-frontend`.
   - **Framework Preset:** `Vite` (auto-detected).
   - **Build Command:** `npm run build`
   - **Output Directory:** `dist`
4. Under **Environment Variables**, add:

| Variable | Value / Description | Example |
|---|---|---|
| `VITE_API_URL` | Your live Render backend URL | `https://docmind-backend.onrender.com` |

*(Note: `frontend/docmind-frontend/vercel.json` ensures all client-side routes redirect cleanly to `index.html`).*

Click **Deploy**. Your DocMind application is now live on the cloud!

---

## Local Development Setup (Windows 10 / 11)

### 1. Prerequisites
- **JDK 21** (`java --version`)
- **MySQL 8.0+** running on `localhost:3306` with database `docmind`
- **Node.js 18+** & npm
- **Google Gemini API Key** from [aistudio.google.com](https://aistudio.google.com/apikey)

### 2. Environment Variables
Copy `.env.example` to `.env` and set:
```powershell
$env:DB_USERNAME="root"
$env:DB_PASSWORD="your_password"
$env:GEMINI_API_KEY="your_gemini_api_key"
$env:JWT_SECRET="your_secure_32_character_jwt_secret_key"
$env:FRONTEND_URL="http://localhost:5173,http://localhost:3000"
```

### 3. Run Backend
```powershell
.\mvnw.cmd clean package -DskipTests
java -jar target\docmind-backend-0.0.1-SNAPSHOT.jar
```
Backend runs on [http://localhost:8081](http://localhost:8081).
Swagger UI: [http://localhost:8081/swagger-ui.html](http://localhost:8081/swagger-ui.html)

### 4. Run Frontend
```powershell
cd frontend\docmind-frontend
npm install
npm run dev
```
Open [http://localhost:3000](http://localhost:3000) or [http://localhost:5173](http://localhost:5173).

---

## Changed Files Summary

| File | Nature of Change |
|---|---|
| `Dockerfile` | Multi-stage Docker build (Maven + Eclipse Temurin Java 21) optimized for Render free tier. |
| `.dockerignore` | Prevents local build artifacts, logs, and sensitive files from entering Docker context. |
| `frontend/docmind-frontend/vercel.json` | Configured SPA fallback rewrite (`/* -> /index.html`) for client-side routing. |
| `frontend/docmind-frontend/src/services/api.ts` | Dynamically reads `VITE_API_URL` with automatic `/api/v1` fallback for dev proxy. |
| `frontend/docmind-frontend/.env.example` | Template for frontend environment variables. |
| `src/main/resources/application.yaml` | Configured `server.port: ${PORT:8081}`. |
| `src/main/resources/application-dev.yml` | Hardcoded secrets moved to env vars (`DB_URL`, `GEMINI_API_KEY`, `JWT_SECRET`, `FRONTEND_URL`). |
| `src/main/java/com/substring/docmind/config/CorsConfig.java` | Configured Spring Security and Spring MVC CORS bean reading from `FRONTEND_URL`. |
| `src/test/java/com/substring/docmind/DocmindBackendApplicationTests.java` | Isolated test properties to enable CI/CD Maven builds without external secrets. |
| `.gitignore` | Enforced exclusion of `.env`, `target/`, `node_modules/`, `.idea/`, and `*.log`. |
| `.env.example` | Updated with full cloud deployment documentation. |
| `README.md` | Comprehensive free cloud deployment instructions and credits to LearnCodeWithDurgesh. |
