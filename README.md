# Adaptive Inclusive Smart Tutor — Phase 1

A full-stack adaptive tutor: **React (Vite) → Spring Boot REST API → PostgreSQL → Q-learning adaptive engine.**

> Student profile → choose subject → choose chapter → learn with the AI tutor → answer questions →
> performance is recorded → the RL engine updates its state and picks the next activity/difficulty.

## Run it

Prerequisites: Java 17+, Maven, Node 18+, PostgreSQL 14+.

```bash
# 1. Database (Homebrew example)
brew services start postgresql@16
createdb adaptive_tutor

# 2. Backend  (http://localhost:8080) — Flyway creates the schema, the seeder loads the curriculum
cd backend
mvn spring-boot:run
#   override connection with DB_URL / DB_USERNAME / DB_PASSWORD if needed

# 3. Frontend (http://localhost:5173) — proxies /api to the backend
cd frontend
npm install
npm run dev
```

Run the tests (RL engine, completion, lesson composer, curriculum answers): `cd backend && mvn test`

## Deploy (Render)

The repo includes a `Dockerfile` (builds the React app into the Spring Boot jar, so one service serves both)
and a `render.yaml` Blueprint (web service + PostgreSQL, free plan).

1. Sign in at [render.com](https://render.com) with GitHub.
2. **New → Blueprint**, pick this repo, click **Apply**.
3. When the build finishes, open the `adaptive-tutor` service URL (`https://adaptive-tutor-xxxx.onrender.com`).

Every push to `main` redeploys automatically. On the free plan the service sleeps after ~15 min idle
(first visit then takes ~30–60 s) and the free database expires after ~30 days.

## Project layout

```
backend/
  src/main/java/com/aist/tutor/
    domain/           JPA entities: Student, Subject, Chapter, Question, LearningProgress, AnswerLog, QValue, AuthSession
    repository/       Spring Data repositories
    personalization/  AccommodationService (needs -> accommodations), LessonComposer (step-by-step + guided examples)
    adaptive/         RL engine (see below) — replaceable via the AdaptivePolicy interface
    tutor/            TutorEngine interface + RuleBasedTutorEngine (swap in an LLM/RAG engine later)
    service/          Auth, Student, Curriculum (grade-scoped), Learning (the loop), Completion, Progress
    web/              REST controllers, DTOs, AuthInterceptor
    seed/             CurriculumSeeder (syncs seed/curriculum.json into the DB on startup)
  src/main/resources/db/migration/   PostgreSQL schema (V1 initial, V2 accounts + completion)
  curriculum-src/     Python sources for the curriculum (run build_curriculum.py to regenerate the JSON)
frontend/
  src/pages/          LoginPage, SignupPage, Dashboard, LearningPage
  src/components/     ProgressSection, StepByStepLesson, GuidedExamples, ReadAlong, AnimatedVisual,
                      Celebration, ActivityCard, TutorChat, AdaptivePanel, LessonContent
```

## REST API

| Method | Path | Purpose |
|---|---|---|
| GET  | `/api/grades` | Grades that have curriculum |
| POST | `/api/auth/signup` | Create account (profile + password) → token |
| POST | `/api/auth/login` | Email + password → token |
| GET  | `/api/auth/me` | Current student (restores the session on reload) |
| POST | `/api/auth/logout` | End the session |
| GET  | `/api/students/{id}` | Profile + accommodation settings |
| GET  | `/api/students/{id}/subjects` | Subjects for the student's grade |
| GET  | `/api/students/{id}/subjects/{subjectId}/chapters` | Chapters (403 if another grade) |
| GET  | `/api/students/{id}/progress` | Completed / in progress / left, per subject, "continue" chapter |
| GET  | `/api/students/{id}/chapters/{chapterId}/lesson` | Lesson adapted to the profile |
| POST | `/api/students/{id}/chapters/{chapterId}/next-activity` | **RL chooses the next activity** |
| POST | `/api/students/{id}/chapters/{chapterId}/answers` | Submit answer → reward → Q-update |
| POST | `/api/students/{id}/tutor/chat` | Talk to the tutor |

All `/api/students/{id}/**` endpoints need `Authorization: Bearer <token>`, and a student can only access
their own data (401 without a valid token, 403 for another student's id).

## Accounts, progress and "continue where you left off"

- **Sign up** is the student profile form plus a password (min 8 characters, stored as a BCrypt hash).
  **Log in** with email + password. Sessions last 30 days; only a SHA-256 hash of each token is stored.
- **Chapter completion:** a chapter is complete when the student has answered 5 different questions
  correctly, including at least one Hard question (`CompletionService`). Progress is shown as a percentage.
- **Dashboard:** a "Continue where you left off" card (most recent unfinished chapter, or the next new one),
  an overall progress ring, completed / in progress / chapters left, and a progress bar per subject and chapter.
- **Resuming:** if a question was open when the student left, the learning page reopens it on the Practice tab.

## The adaptive / RL engine

**State** (`LearnerState`, 54 states) = difficulty (EASY/MEDIUM/HARD) × recent performance
(STRUGGLING/STEADY/STRONG, from the last 4 questions) × pace (FAST/ON_TRACK/SLOW, time vs. expected,
scaled by accommodations) × readiness (enough first-try correct answers in a row to level up).

**Actions** (`TutorAction`): `REVIEW_EASIER` (step down + re-teach key points), `WORKED_EXAMPLE`,
`PRACTICE` (same level), `LEVEL_UP`.

**Reward** (`RewardFunction`): first-try correct = +0.6 / +1.0 / +1.4 by difficulty; correct after a hint
= +0.3; not solved = −1.0; very slow −0.2; under-challenge (strong student kept at the same non-hard level)
−0.4.

**Policy** (`QLearningPolicy`): tabular Q-learning, one Q-table per student (stored in `q_value`), ε-greedy,
α = 0.3, γ = 0.6. Unvisited entries start from small "teacher intuition" priors.

**Loop**: `next-activity` encodes the state and picks an action (stored as *pending* on `learning_progress`)
→ student answers (with retries + hints) → `answers` computes the reward, encodes the next state, and applies
`Q(s,a) ← Q(s,a) + α[r + γ·maxQ(s',·) − Q(s,a)]`. The UI's "How your tutor is adapting" panel shows the state,
Q-values, chosen action, reward and Q-update live.

**Guard-rails** outside the learned policy: no levelling up while struggling or until the profile's
streak requirement is met.

To replace the algorithm, implement `AdaptivePolicy` (e.g. a contextual bandit or DKT-based policy).

## Learning-needs accommodations (not diagnosis)

| Need | Adjustments |
|---|---|
| Dyslexia | Highly legible font + spacing, key-point text, read-aloud, 3 tries, extra time, slower step-up |
| ADHD | 2-sentence chunks, frequent questions, break reminder every 4 questions |
| Autism | Fixed Learn → Examples → Practice structure, explicit "what to do next", calm UI, low exploration |
| Down Syndrome | Simple words, 1-idea chunks, visual examples first, read-aloud, 2× time, 4-in-a-row to level up |
| Other | Extra tries and time |

Multiple needs combine, and the most supportive setting wins.

On top of these, accommodated learners get a visibly different learning page:

| Feature | Who gets it | What it does |
|---|---|---|
| Step-by-step lesson | all needs | One idea at a time, each with "Tell me more" (the fuller explanation sentence) and its own example |
| Guided examples | Dyslexia, Autism, Down Syndrome, Other | "Watch how to solve it": read → think (hint) → answer + why, revealed step by step |
| Read-along | Dyslexia, Down Syndrome | Text is read aloud with each word highlighted as it is spoken |
| Animated pictures | Dyslexia, ADHD, Down Syndrome | Picture examples draw in piece by piece, with "Play again" |
| Celebrations | all needs | Confetti on correct answers and a trophy when a chapter is completed (calm, still version for Autism) |
| Focus mode | ADHD, Autism | Learn / Examples / Practice tabs — one section on screen at a time; break meter for ADHD |

Step-by-step material is built automatically from each chapter (`LessonComposer`), so new chapters get it
too. Questions shown as guided examples are served last in practice. Animations respect
`prefers-reduced-motion` and calm mode.

## Curriculum

Predefined in `seed/curriculum.json`: grades 6–12, 193 chapters, 1,194 questions. Every chapter has an
explanation, key points, examples, an activity and questions at all three difficulty levels.

| Grades | Subjects |
|---|---|
| 6–8 | Mathematics, Science, English, Social Science |
| 9–10 | Mathematics, **Physics, Chemistry, Biology** (Science split), English, Social Science |
| 11–12 | Mathematics, Physics, Chemistry, Biology, English, Computer Science, Economics |

Every subject has 5 chapters (Grade 6 Maths, Science and English have 6). Extra chapters live in
`curriculum-src/moreN.py` and are appended after the existing ones. The board is stored but the curriculum is currently
keyed by grade only.

**Adding content:** edit `backend/curriculum-src/gradeN.py`, run `python3 build_curriculum.py` (it validates
every chapter), and restart the backend. On startup the seeder adds only new subjects (matched by grade + name)
and chapters (matched by order), so existing content and student progress are untouched.
`CurriculumContentTest` checks that every stored answer is accepted by the grader.

## Not in phase 1 (by design)

Parent dashboard, password reset, notifications, admin panel, syllabus upload, RAG/LLM, DKT, deployment, mobile app.
