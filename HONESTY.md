# HONESTY.md

> Mandatory disclosure for the hackathon. This file lives at the root of your repository. Judges cross-check it against your code and your technical video.
>
> **The deal:** disclosed shortcuts are **not** penalized — that is the entire point of this file. Hidden ones are. Undisclosed pre-built code is heavily penalized, each undisclosed mock carries a small penalty, and a faked demo is heavily penalized. Telling the truth here costs you nothing.

---

## 1. Team — who did what
Judges compare this against `git shortlog -sn`, so keep it honest.

| Member | GitHub handle | Main contributions |
|---|---|---|
|Erika|erikasy23||
|Nikola|nikola|Pathfinding Workflow|
|Kaloyan|kfachikov|Frontend and Integration|
|Hristo|hristostefanov-rzr|Inner Representation and Target Matching|
---

## 2. What is fully working
Features that run end-to-end on the live app, with real data and real logic. Be specific: name the feature, what input it takes, what output it produces.

- Visualize floor map in 2D, takes in the map representation, renders a map, and displays it
- Visualize floor map in 3D, takes in the map representation, renders a map, and displays it
- Track the user as they are navigating using Visual Inertial Odometry (VIO), takes in the camera and IMU input, and outputs the user's relative location w.r.t. their starting location, and their orientation  
- Computing a walkable path using Voronoi, takes in the map representation, and outputs a path between the starting location and the target

---

## 3. What is mocked, stubbed, or hardcoded
Every shortcut. Examples: a login that accepts any password, a payment that always succeeds, an "AI" that is an if/else, a database that is an in-memory dictionary, fake JSON returned instead of a real API call.

**Undisclosed mocks carry a small penalty each. Anything you list here = free.**

| What is faked | Where (file:line or folder) | Why we mocked it | What the real version would do |
|---|---|---|---|
| We extract a computer-understandable representation manually | (nowhere) Using ChatGPT | We wanted to focus on showcasing the usability the application has | We would have a web UI platform allowing customers to upload evacuation/floor plans and process them automatically |
| The starting location - where we are on the map when we start the application | In the JSON map representation | Again, we wanted to showcase the navigation | Our tracking mechanism already uses the application camera. Upon entering the building, we can either ask the user to scan the building entrance door, or pinpoint their location using GPS. We can use both data points to locate the building they are entering |
| The (floor plan)-to-(real area) ratio |  | As we manually extracted the map representation, we needed to provide the ratio | We would ask the customer to provide the ratio while uploading the floor plans |
|  |  |  |  |

If nothing is mocked, write: *"Nothing is mocked — every feature listed above uses real logic and real data."*

---

## 4. External APIs, services & data sources
Everything the project calls or pretends to call. Mark each as real or mocked.

| Service / API / dataset | Used for | Real call or mocked? | Auth (sandbox / test key / none) |
|---|---|---|---|
|  |  |  |  |
|  |  |  |  |

---

## 5. Pre-existing code
Anything written **before** kickoff that we brought into this project: prior personal projects, forked open-source code, templates, boilerplate, internal libraries.

**Undisclosed pre-built code is heavily penalized. Anything you list here = free.**

All code in this repo was written during the hackathon window.

| Item | Source (URL or description) | Roughly how much | License |
|---|---|---|---|
|  |  |  |  |
|  |  |  |  |

If none, write: *"All code in this repo was written during the hackathon window."*

---

## 6. Known limitations & next steps
What we would build next, and the weak spots we already know about. Naming these honestly is a strength, not a flaw.

- A new revenue stream we would like to target is our customers (e.g., building owners) directly by providing them with continuous data about typical user patterns while navigating in their buildings. This would be particularly target at shopping centers and malls, as it can help owners identify "sweet spots", or places that people pass by most, and price rents appropriately.
- 
- 
