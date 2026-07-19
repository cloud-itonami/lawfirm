/** @type {import('tailwindcss').Config} */
export default {
  content: ["./src/**/*.{html,svelte,ts,js}"],
  darkMode: "class",
  theme: {
    extend: {
      colors: {
        matter: {
          intake:        "hsl(var(--col-intake))",
          conflictCheck: "hsl(var(--col-conflictCheck))",
          engaged:       "hsl(var(--col-engaged))",
          filed:         "hsl(var(--col-filed))",
          hearing:       "hsl(var(--col-hearing))",
          trial:         "hsl(var(--col-trial))",
          judgment:      "hsl(var(--col-judgment))",
          appeal:        "hsl(var(--col-appeal))",
          execution:     "hsl(var(--col-execution))",
          closed:        "hsl(var(--col-closed))",
          archived:      "hsl(var(--col-archived))",
        },
      },
    },
  },
  plugins: [],
};
