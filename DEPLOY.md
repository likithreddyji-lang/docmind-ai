# DocMind — 100% Free Cloud Deployment Guide

This guide walks you through deploying the complete DocMind application to the cloud at zero cost with full SSL/TLS encryption.

```
┌─────────────────────────────────┐       ┌─────────────────────────────────┐       ┌─────────────────────────────────┐
│     TiDB Cloud Serverless       │◄─────►│    Render Free Web Service      │◄─────►│         Vercel Frontend         │
│   (Free MySQL 8-compatible)    │ (SSL) │  (Spring Boot 4.1.0 on Docker)  │(HTTPS)│     (React 19 + Vite 8 SPA)     │
└─────────────────────────────────┘       └─────────────────────────────────┘       └─────────────────────────────────┘
```

---

## Architecture & Free Tier Breakdown

| Component | Platform | Free Plan Limits | Cost |
|---|---|---|---|
| **Database** | [TiDB Cloud](https://tidbcloud.com) (Serverless) | 5 GiB storage, 50M Request Units / month | **$0.00** |
| **Backend** | [Render](https://render.com) (Web Service) | 512 MB RAM, 0.1 CPU, shared instance | **$0.00** |
| **Frontend** | [Vercel](https://vercel.com) (Hobby) | 100 GB bandwidth, unlimited deployments | **$0.00** |
| **AI LLM & Vectors** | [Google AI Studio](https://aistudio.google.com) | Free tier: `gemini-3.5-flash` & `gemini-embedding-001` | **$0.00** |

---

## Part 1: Setup Free Cloud Database (TiDB Cloud)

TiDB Cloud Serverless provides a fully managed, MySQL 8-compatible distributed database with automatic SSL.

1. **Sign Up:** Go to [tidbcloud.com](https://tidbcloud.com) and create a free account.
2. **Create Cluster:**
   - Click **Create Cluster**.
   - Select **Serverless** (Free tier).
   - Choose your preferred cloud region (e.g., `AWS / us-east-1`).
   - Cluster Name: `docmind-db`.
   - Click **Create**.
3. **Set Root Password:**
   - When prompted, generate or choose a secure password (e.g., `FakeDbPass_98765!`).
   - Save this password securely.
4. **Get Connection Parameters:**
   - On the cluster overview page, click **Connect**.
   - Select **Connection Method:** `General` &rarr; Driver: `JDBC`.
   - Notice the connection details:
     - **Host:** e.g., `gateway01.us-east-1.prod.aws.tidbcloud.com`
     - **Port:** `4000`
     - **User:** e.g., `2aBcDeFg.root`
     - **Database:** `test` (change this to `docmind`)
5. **Construct Your JDBC URL with SSL:**
   ```text
   jdbc:mysql://gateway01.us-east-1.prod.aws.tidbcloud.com:4000/docmind?sslMode=VERIFY_IDENTITY&enabledTLSProtocols=TLSv1.2,TLSv1.3&allowPublicKeyRetrieval=true&serverTimezone=UTC
   ```
   *(Note: Hibernate with `ddl-auto: update` will automatically create all tables and the `vector_store` table on first startup).*

---

## Part 2: Deploy Backend to Render (Docker Web Service)

Render will build and run the multi-stage [`Dockerfile`](file:///c:/Users/Admin/Desktop/docmind-ai2/Dockerfile) with memory-optimized JVM flags (`-XX:MaxRAMPercentage=70.0 -XX:+UseSerialGC -Xss512k`).

### Option A: Deploy via Blueprint (Fastest)

1. Push your repository to your private GitHub or GitLab account (ensure `.env` is ignored).
2. Log in to [render.com](https://render.com).
3. Click **New +** &rarr; **Blueprint**.
4. Connect your repository. Render will automatically detect [`render.yaml`](file:///c:/Users/Admin/Desktop/docmind-ai2/render.yaml).
5. Render will display a form prompting you for the environment variables marked with `sync: false`.
6. Fill in the values (see table below) and click **Apply**.

### Option B: Deploy Manually

1. Log in to [render.com](https://render.com).
2. Click **New +** &rarr; **Web Service**.
3. Connect your repository.
4. Configure service settings:
   - **Name:** `docmind-backend`
   - **Region:** Same or close to your TiDB region (e.g., `Oregon (US West)` or `Ohio (US East)`)
   - **Runtime:** `Docker` (Render automatically uses the root `Dockerfile`)
   - **Instance Type:** `Free` (512 MB RAM)
   - **Health Check Path:** `/health`
5. Click **Advanced** &rarr; **Add Environment Variable** for each of the following:

### Backend Environment Variables Table

| Key | Description | Example Value |
|---|---|---|
| `DB_URL` | TiDB JDBC connection string with SSL | `jdbc:mysql://gateway01.us-east-1.prod.aws.tidbcloud.com:4000/docmind?sslMode=VERIFY_IDENTITY&enabledTLSProtocols=TLSv1.2,TLSv1.3&allowPublicKeyRetrieval=true&serverTimezone=UTC` |
| `DB_USERNAME` | TiDB user | `2aBcDeFg.root` |
| `DB_PASSWORD` | TiDB password | `FakeDbPass_98765!` |
| `GEMINI_API_KEY` | Google AI Studio free API key | `AIzaSyFakeKeyExample1234567890abcdef` |
| `JWT_SECRET` | Random 256-bit string (min 32 characters) | `super_secure_random_jwt_key_at_least_32_characters_long!` |
| `FRONTEND_URL` | URL of your Vercel deployment (no trailing slash!) | `https://docmind-ai.vercel.app` *(use `http://localhost:5173` temporarily until Vercel is deployed)* |

6. Click **Create Web Service**.
7. Wait 2–3 minutes for the build to complete. Once live, Render displays your service URL:
   `https://docmind-backend-xyz.onrender.com`
8. Verify health in your browser or terminal:
   ```bash
   curl -i https://docmind-backend-xyz.onrender.com/health
   ```
   Expected response:
   ```json
   {"service":"docmind-backend","status":"UP","timestamp":"2026-11-07T12:00:00Z"}
   ```

---

## Part 3: Deploy Frontend to Vercel

1. Log in to [vercel.com](https://vercel.com).
2. Click **Add New...** &rarr; **Project**.
3. Import your GitHub repository.
4. In the **Configure Project** screen:
   - **Framework Preset:** `Vite` (automatically detected).
   - **Root Directory:** Click **Edit** and choose `frontend/docmind-frontend`.
   - **Build Command:** `npm run build` (default).
   - **Output Directory:** `dist` (default).
5. Expand **Environment Variables** and add:

| Key | Description | Example Value |
|---|---|---|
| `VITE_API_URL` | Your live Render backend URL (no trailing slash) | `https://docmind-backend-xyz.onrender.com` |

6. Click **Deploy**.
7. In ~30 seconds, Vercel will complete the build and assign your live domain:
   `https://docmind-ai.vercel.app`

*(Note: [`vercel.json`](file:///c:/Users/Admin/Desktop/docmind-ai2/frontend/docmind-frontend/vercel.json) handles SPA client routing so reloading deep links like `/chat` will never 404).*

---

## Part 4: Connect Frontend to Backend (Final CORS Step)

1. Copy your live Vercel domain: `https://docmind-ai.vercel.app`.
2. Go back to your [Render Dashboard](https://dashboard.render.com) &rarr; `docmind-backend` &rarr; **Environment**.
3. Update `FRONTEND_URL` to your Vercel domain:
   `FRONTEND_URL=https://docmind-ai.vercel.app`
   *(Ensure there is **no** trailing slash at the end).*
4. Click **Save Changes**. Render will automatically trigger a rolling redeploy.

---

## Part 5: Post-Deployment Verification Checklist

Once both services are running, run through this verification checklist:

- [ ] **1. Public Health Check:**
  - Visit `https://docmind-backend-xyz.onrender.com/health`.
  - Should return HTTP 200 with `{"status":"UP"}` without requiring a login.
- [ ] **2. Swagger Documentation:**
  - Visit `https://docmind-backend-xyz.onrender.com/swagger-ui.html`.
  - Interactive API docs should load.
- [ ] **3. User Registration:**
  - Open your Vercel frontend URL `https://docmind-ai.vercel.app/register`.
  - Register a new account (e.g. `testuser@example.com`).
  - Verify redirection to `/login`.
- [ ] **4. User Authentication:**
  - Log in with your new credentials.
  - Confirm dashboard loads and JWT token is stored.
- [ ] **5. Document Upload & Chunking:**
  - Go to the documents page and upload a sample PDF.
  - Verify the status transitions to `INDEXED` and the chunk count is displayed.
- [ ] **6. Document-Grounded Q&A:**
  - Navigate to `/chat`.
  - Ask a question specifically answered in the uploaded document.
  - Confirm the AI response cites the document name and page number.
- [ ] **7. Real-Time Streaming:**
  - Ask another question and verify tokens stream smoothly in real time over SSE.
- [ ] **8. SPA Reload Verification:**
  - Refresh the browser while on `https://docmind-ai.vercel.app/chat`.
  - Confirm the page reloads cleanly without a 404 error.

