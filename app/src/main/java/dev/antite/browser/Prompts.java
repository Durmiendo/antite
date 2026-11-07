package dev.antite.browser;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.text.InputType;
import android.widget.EditText;

import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSession.PromptDelegate;

import java.util.ArrayList;
import java.util.List;

final class Prompts implements PromptDelegate {
    static final int FILE_REQUEST = 4242;

    private final Activity activity;
    private FilePrompt pendingFile;
    private GeckoResult<PromptResponse> pendingFileResult;
    java.util.function.Predicate<GeckoSession> popupPolicy;

    Prompts(Activity activity) {
        this.activity = activity;
    }

    private Sheet builder(BasePrompt p) {
        Sheet b = new Sheet(activity);
        if (p.title != null && !p.title.isEmpty()) b.setTitle(p.title);
        return b;
    }

    @Override
    public GeckoResult<PromptResponse> onAlertPrompt(GeckoSession s, AlertPrompt p) {
        GeckoResult<PromptResponse> r = new GeckoResult<>();
        builder(p).setMessage(p.message)
                .setPositiveButton("OK", (d, w) -> r.complete(p.dismiss()))
                .setOnCancelListener(d -> r.complete(p.dismiss()))
                .show();
        return r;
    }

    @Override
    public GeckoResult<PromptResponse> onButtonPrompt(GeckoSession s, ButtonPrompt p) {
        GeckoResult<PromptResponse> r = new GeckoResult<>();
        builder(p).setMessage(p.message)
                .setPositiveButton("OK", (d, w) -> r.complete(p.confirm(ButtonPrompt.Type.POSITIVE)))
                .setNegativeButton("Отмена", (d, w) -> r.complete(p.confirm(ButtonPrompt.Type.NEGATIVE)))
                .setOnCancelListener(d -> r.complete(p.dismiss()))
                .show();
        return r;
    }

    @Override
    public GeckoResult<PromptResponse> onTextPrompt(GeckoSession s, TextPrompt p) {
        GeckoResult<PromptResponse> r = new GeckoResult<>();
        EditText input = new EditText(activity);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        if (p.defaultValue != null) input.setText(p.defaultValue);
        builder(p).setMessage(p.message).setView(input)
                .setPositiveButton("OK", (d, w) -> r.complete(p.confirm(input.getText().toString())))
                .setNegativeButton("Отмена", (d, w) -> r.complete(p.dismiss()))
                .setOnCancelListener(d -> r.complete(p.dismiss()))
                .show();
        return r;
    }

    private static void flatten(ChoicePrompt.Choice[] in, List<ChoicePrompt.Choice> out) {
        for (ChoicePrompt.Choice c : in) {
            if (c.separator) continue;
            if (c.items != null) {
                flatten(c.items, out);
            } else {
                out.add(c);
            }
        }
    }

    @Override
    public GeckoResult<PromptResponse> onChoicePrompt(GeckoSession s, ChoicePrompt p) {
        GeckoResult<PromptResponse> r = new GeckoResult<>();
        List<ChoicePrompt.Choice> list = new ArrayList<>();
        flatten(p.choices, list);
        String[] labels = new String[list.size()];
        boolean[] checked = new boolean[list.size()];
        for (int i = 0; i < list.size(); i++) {
            labels[i] = list.get(i).label;
            checked[i] = list.get(i).selected;
        }
        Sheet b = builder(p);
        if (p.type == ChoicePrompt.Type.MULTIPLE) {
            b.setMultiChoiceItems(labels, checked, (d, w, on) -> checked[w] = on)
                    .setPositiveButton("OK", (d, w) -> {
                        List<String> ids = new ArrayList<>();
                        for (int i = 0; i < checked.length; i++) {
                            if (checked[i]) ids.add(list.get(i).id);
                        }
                        r.complete(p.confirm(ids.toArray(new String[0])));
                    });
        } else {
            int sel = -1;
            for (int i = 0; i < checked.length; i++) {
                if (checked[i]) sel = i;
            }
            b.setSingleChoiceItems(labels, sel, (d, w) -> {
                r.complete(p.confirm(list.get(w)));
                d.dismiss();
            });
        }
        b.setOnCancelListener(d -> r.complete(p.dismiss())).show();
        return r;
    }

    @Override
    public GeckoResult<PromptResponse> onPopupPrompt(GeckoSession s, PopupPrompt p) {
        boolean allow = popupPolicy == null || popupPolicy.test(s);
        return GeckoResult.fromValue(p.confirm(allow ? AllowOrDeny.ALLOW : AllowOrDeny.DENY));
    }

    @Override
    public GeckoResult<PromptResponse> onBeforeUnloadPrompt(GeckoSession s, BeforeUnloadPrompt p) {
        GeckoResult<PromptResponse> r = new GeckoResult<>();
        new Sheet(activity)
                .setMessage("Покинуть страницу? Изменения могут не сохраниться.")
                .setPositiveButton("Покинуть", (d, w) -> r.complete(p.confirm(AllowOrDeny.ALLOW)))
                .setNegativeButton("Остаться", (d, w) -> r.complete(p.confirm(AllowOrDeny.DENY)))
                .setOnCancelListener(d -> r.complete(p.confirm(AllowOrDeny.DENY)))
                .show();
        return r;
    }

    @Override
    public GeckoResult<PromptResponse> onRepostConfirmPrompt(GeckoSession s, RepostConfirmPrompt p) {
        GeckoResult<PromptResponse> r = new GeckoResult<>();
        new Sheet(activity)
                .setMessage("Отправить данные формы повторно?")
                .setPositiveButton("Отправить", (d, w) -> r.complete(p.confirm(AllowOrDeny.ALLOW)))
                .setNegativeButton("Отмена", (d, w) -> r.complete(p.confirm(AllowOrDeny.DENY)))
                .setOnCancelListener(d -> r.complete(p.confirm(AllowOrDeny.DENY)))
                .show();
        return r;
    }

    @Override
    public GeckoResult<PromptResponse> onFilePrompt(GeckoSession s, FilePrompt p) {
        if (pendingFileResult != null) return GeckoResult.fromValue(p.dismiss());
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        String mime = p.mimeTypes != null && p.mimeTypes.length == 1 ? p.mimeTypes[0] : "*/*";
        i.setType(mime.contains("/") ? mime : "*/*");
        if (p.type == FilePrompt.Type.MULTIPLE) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        pendingFile = p;
        pendingFileResult = new GeckoResult<>();
        GeckoResult<PromptResponse> r = pendingFileResult;
        activity.startActivityForResult(Intent.createChooser(i, null), FILE_REQUEST);
        return r;
    }

    void onFileResult(int resultCode, Intent data) {
        if (pendingFileResult == null) return;
        FilePrompt p = pendingFile;
        GeckoResult<PromptResponse> r = pendingFileResult;
        pendingFile = null;
        pendingFileResult = null;
        if (resultCode != Activity.RESULT_OK || data == null) {
            r.complete(p.dismiss());
            return;
        }
        if (data.getClipData() != null) {
            Uri[] uris = new Uri[data.getClipData().getItemCount()];
            for (int i = 0; i < uris.length; i++) uris[i] = data.getClipData().getItemAt(i).getUri();
            r.complete(p.confirm(activity, uris));
        } else if (data.getData() != null) {
            r.complete(p.confirm(activity, data.getData()));
        } else {
            r.complete(p.dismiss());
        }
    }
}
