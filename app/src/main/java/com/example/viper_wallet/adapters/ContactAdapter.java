package com.example.viper_wallet.adapters;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.viper_wallet.R;
import com.example.viper_wallet.auth.AuthManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class ContactAdapter extends RecyclerView.Adapter<ContactAdapter.ViewHolder> {

    public interface OnContactClickListener {
        void onContactClick(AuthManager.Contact contact);
    }

    private List<AuthManager.Contact> contacts = new ArrayList<>();
    private final OnContactClickListener listener;

    public ContactAdapter(OnContactClickListener listener) {
        this.listener = listener;
    }

    public void setContacts(List<AuthManager.Contact> contacts) {
        this.contacts = contacts;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_contact, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        AuthManager.Contact contact = contacts.get(position);
        holder.bind(contact, listener);
    }

    @Override
    public int getItemCount() {
        return contacts.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        private final TextView tvName;
        private final TextView tvAddress;
        private final TextView tvInitial;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvName = itemView.findViewById(R.id.tvContactName);
            tvAddress = itemView.findViewById(R.id.tvContactAddress);
            tvInitial = itemView.findViewById(R.id.tvContactInitial);
        }

        public void bind(AuthManager.Contact contact, OnContactClickListener listener) {
            String name = contact.getName() != null && !contact.getName().isEmpty() ? contact.getName() : "Sin nombre";
            tvName.setText(name);
            tvAddress.setText(contact.getPublicKey());
            
            String initial = name.substring(0, 1).toUpperCase(Locale.US);
            tvInitial.setText(initial);
            
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.OVAL);
            bg.setColor(getContactColor(name));
            tvInitial.setBackground(bg);

            itemView.setOnClickListener(v -> listener.onContactClick(contact));
        }

        private int getContactColor(String name) {
            int[] colors = {
                    Color.parseColor("#EF5350"), Color.parseColor("#EC407A"), Color.parseColor("#AB47BC"),
                    Color.parseColor("#7E57C2"), Color.parseColor("#5C6BC0"), Color.parseColor("#42A5F5"),
                    Color.parseColor("#26A69A"), Color.parseColor("#66BB6A"), Color.parseColor("#FFA726"),
                    Color.parseColor("#FF7043"), Color.parseColor("#8D6E63"), Color.parseColor("#78909C")
            };
            return colors[Math.abs(name.hashCode()) % colors.length];
        }
    }
}
