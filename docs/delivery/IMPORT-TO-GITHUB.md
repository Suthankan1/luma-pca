# Import the ZIP into the existing GitHub repository

Repository: https://github.com/Suthankan1/luma-pca

The repository was read at bootstrap time and was empty; **nothing in this ZIP has been pushed by ChatGPT**. To use this archive as the entire project folder:

```bash
unzip luma-pca.zip
cd luma-pca
python3 scripts/verify_structure.py
# Run build commands once local Java, Maven, Node and internet access are available.
git init -b main
git add .
git commit -m "chore: bootstrap LUMA monorepo and engineering specs"
git remote add origin https://github.com/Suthankan1/luma-pca.git
git push -u origin main
```

If the remote gained files or commits since ZIP creation, **do not force-push**. Clone it into a separate folder, copy these files carefully, review the diff and commit on a topic branch/PR.

After pushing, GitHub Actions frontend/backend jobs should validate builds. npm uses `npm install` for initial dependency resolution because this offline-generated ZIP has **no package-lock.json**. After the first successful install, commit `frontend/package-lock.json` and switch CI to `npm ci`. Do not treat initial CI templates as evidence of successful CI until runs finish green.

Initial bootstrap screenshots and old HTML demo are references only. Inspect `LICENSE_DECISION_REQUIRED.md` before making public reuse claims.
