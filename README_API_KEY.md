# Setting Up Gemini API Key for AI Coach Insights

The AI Coach Insights feature uses Google's Gemini API to provide personalized eco-fitness feedback. To enable this feature, you need to add your Gemini API key.

## Steps to Add API Key

### 1. Get Your Gemini API Key

1. Go to [Google AI Studio](https://aistudio.google.com/app/apikey)
2. Sign in with your Google account
3. Click "Create API Key" or "Get API Key"
4. Copy your API key

### 2. Create `.env` File

Create a `.env` file in the root of your project (same directory as `package.json`) with the following content:

```env
VITE_GEMINI_API_KEY=your-actual-api-key-here
```

**Important:** Replace `your-actual-api-key-here` with the actual API key you copied.

### 3. Restart Development Server

After creating the `.env` file:
- Stop your development server (Ctrl+C)
- Restart it with `npm run dev`

### 4. For Production Deployment

For Firebase Hosting deployment, you need to set environment variables in Firebase:

1. Go to [Firebase Console](https://console.firebase.google.com/)
2. Select your project
3. Go to **Hosting** → **Add custom domain** or **Configure**
4. Add environment variable `VITE_GEMINI_API_KEY` with your API key value

Alternatively, you can use Firebase Functions to proxy the API calls and keep the key server-side (more secure).

## Security Notes

- ⚠️ **Never commit your `.env` file to Git** - it's already in `.gitignore`
- ⚠️ **Never share your API key publicly**
- ⚠️ **For production**, consider using Firebase Functions to keep the API key server-side

## Testing

After adding the API key:
1. Start tracking a session
2. Stop tracking to view Analytics
3. The AI Coach Insight should appear with personalized feedback

If you see an error message, check:
- The `.env` file exists in the project root
- The API key is correct (no extra spaces)
- You've restarted the dev server after creating `.env`















